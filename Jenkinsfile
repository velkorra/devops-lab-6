pipeline {
    agent any

    triggers {
        pollSCM('H/2 * * * *')
    }
    environment {
        VAULT_URL = 'http://vault:8200'
        DOCKER_HOST = 'tcp://docker-dind:2376'
        REGISTRY_URL = 'registry:443'
        DOCKER_BUILDKIT = '1'
        
        APP_NAME = "work-app"
        NAMESPACE = "lab6"
        NEW_TAG = "v${env.BUILD_NUMBER}"
        IMAGE_NAME = "${REGISTRY_URL}/${APP_NAME}"

        TEST_URL = "http://work-app.lab6.svc.cluster.local:80/work"
        
        OLD_IMAGE = ""
    }

    stages {
        stage('Init & Get Current State') {
            steps {
                script {
                    echo "=> Ищем текущий образ для возможного rollback'а..."
                    try {
                        OLD_IMAGE = sh(
                            script: "kubectl get deployment ${APP_NAME} -n ${NAMESPACE} -o jsonpath='{.spec.template.spec.containers[0].image}'", 
                            returnStdout: true
                        ).trim()
                        echo "=> Нашли текущий образ: ${OLD_IMAGE}"
                    } catch (Exception e) {
                        echo "=> Деплоймент пока не существует (это первый запуск). Откат будет невозможен."
                        OLD_IMAGE = "NONE"
                    }
                }
            }
        }

        stage('Auth & Issue Certs (Vault)') {
            steps {
                withCredentials([
                    string(credentialsId: 'vault-role-id', variable: 'ROLE_ID'),
                    string(credentialsId: 'vault-secret-id', variable: 'SECRET_ID')
                ]) {
                    sh '''#!/bin/bash
                        set -e 
                        set +x
                        
                        echo "=> Авторизация в Vault через AppRole..."
                        TOKEN_JSON=$(curl -s -X POST -d '{"role_id":"'$ROLE_ID'","secret_id":"'$SECRET_ID'"}' $VAULT_URL/v1/auth/approle/login)
                        VAULT_TOKEN=$(echo $TOKEN_JSON | grep -o '"client_token":"[^"]*' | cut -d'"' -f4)
                        
                        echo "=> Выпуск mTLS сертификата для Jenkins..."
                        curl -s -H "X-Vault-Token: $VAULT_TOKEN" -X POST -d '{"common_name":"jenkins"}' $VAULT_URL/v1/pki/issue/cicd-role > cert.json
                        
                        grep -o '"issuing_ca":"[^"]*' cert.json | cut -d'"' -f4 | perl -pe 's/\\\\n/\\n/g' > ca.crt
                        grep -o '"certificate":"[^"]*' cert.json | cut -d'"' -f4 | perl -pe 's/\\\\n/\\n/g' > client.crt
                        grep -o '"private_key":"[^"]*' cert.json | cut -d'"' -f4 | perl -pe 's/\\\\n/\\n/g' > client.key
                        
                        echo "=> Получение учетных данных Registry из Vault..."
                        CREDS_JSON=$(curl -s -H "X-Vault-Token: $VAULT_TOKEN" $VAULT_URL/v1/secret/data/registry/writer)
                        REG_USER=$(echo $CREDS_JSON | grep -o '"username":"[^"]*' | head -1 | cut -d'"' -f4)
                        REG_PASS=$(echo $CREDS_JSON | grep -o '"password":"[^"]*' | head -1 | cut -d'"' -f4)
                        
                        echo -n "$REG_USER" > reg_user
                        echo -n "$REG_PASS" > reg_pass
                    '''
                }
            }
        }

        stage('Build & Push Docker Image') {
            steps {
                sh '''#!/bin/bash
                    set -e
                    set +x
                    
                    REG_USER=$(cat reg_user)
                    REG_PASS=$(cat reg_pass)
                    
                    echo "=> Логинимся в Registry под юзером $REG_USER..."
                    echo "$REG_PASS" | docker --tlsverify --tlscacert=ca.crt --tlscert=client.crt --tlskey=client.key login $REGISTRY_URL -u "$REG_USER" --password-stdin
                    
                    echo "=> Сборка образа с новым тегом ${NEW_TAG}..."
                    docker --tlsverify --tlscacert=ca.crt --tlscert=client.crt --tlskey=client.key build -t ${IMAGE_NAME}:${NEW_TAG} .
                    
                    echo "=> Пуш образа в приватный Registry..."
                    docker --tlsverify --tlscacert=ca.crt --tlscert=client.crt --tlskey=client.key push ${IMAGE_NAME}:${NEW_TAG}
                '''
            }
        }

        stage('Deploy to K8s') {
            steps {
                script {
                    echo "=> Подменяем тег в манифесте..."
                    sh "sed -i 's|__IMAGE_TAG__|${NEW_TAG}|g' k8s/app.yaml"
                    
                    echo "=> Применяем конфигурацию в кластер..."
                    sh "kubectl apply -f k8s/ -n ${NAMESPACE}"
                    
                    echo "=> ЖДЕМ завершения rollout'а (без этого тесты пойдут в старые поды)..."
                    sh "kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=180s"
                }
            }
        }

        stage('Load Testing (Warmup & Main)') {
            steps {
                script {
                    try {
                        // Качаем Go прямо в Jenkins-под (в shared-tools) чтобы не мучаться с вольюмами DinD
                        sh '''#!/bin/bash
                            set -e
                            if ! command -v go &> /dev/null; then
                                echo "=> Устанавливаем Go в Jenkins..."
                                curl -sLo go.tar.gz https://go.dev/dl/go1.21.6.linux-amd64.tar.gz
                                tar -C /shared-tools -xzf go.tar.gz
                                rm go.tar.gz
                            fi
                            export PATH=$PATH:/shared-tools/go/bin
                            
                            cd load-tester
                            echo "=> Запуск прогрева HPA (1 минута)..."
                            go run main.go -url ${TEST_URL} -rps 100 -duration 1m
                            
                            echo "=> Ждем 15 секунд, чтобы HPA поднял реплики..."
                            sleep 15
                            
                            echo "=> Запуск боевого теста (2 минуты)..."
                            go run main.go -url ${TEST_URL} -rps 480 -duration 2m
                        '''
                    } catch (Exception e) {
                        error "Load testing failed to execute: ${e.message}"
                    }
                }
            }
        }

        stage('Analyze Results & Decision') {
            steps {
                script {
                    echo "=> Анализ отчета нагрузочного тестирования..."
                    
                    // Парсим последний сгенерированный txt файл отчета
                    def reportFile = sh(script: "ls -t load-tester/load_test_report_*.txt | head -n 1", returnStdout: true).trim()
                    
                    // Выдираем проценты успеха и RPS с помощью awk
                    def successRateStr = sh(script: "awk '/Успешность:/ {print \$2}' ${reportFile} | tr -d '%'", returnStdout: true).trim()
                    def targetRpsStr = sh(script: "awk '/Достигнуто цели \\(успешный RPS\\):/ {print \$5}' ${reportFile} | tr -d '%'", returnStdout: true).trim()
                    
                    // Делаем float, чтобы можно было сравнить (для русской локали заменяем запятую на точку, если она есть)
                    def successRate = successRateStr.replace(',', '.').toFloat()
                    def targetRps = targetRpsStr.replace(',', '.').toFloat()
                    
                    echo "Результат: Успешность = ${successRate}%, Достижение RPS = ${targetRps}%"
                    
                    // Логика проверки из задания (меньше 95% = неуспешный релиз)
                    if (successRate < 95.0 || targetRps < 95.0) {
                        echo "=> 🚨 ПРОБЛЕМА ПРОИЗВОДИТЕЛЬНОСТИ! Инициируем Rollback..."
                        
                        if (OLD_IMAGE != "NONE") {
                            sh "kubectl set image deployment/${APP_NAME} ${APP_NAME}=${OLD_IMAGE} -n ${NAMESPACE}"
                            sh "kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=180s"
                            error "Пайплайн упал: производительность просела. Успешно откатились на ${OLD_IMAGE}."
                        } else {
                            error "Пайплайн упал: производительность просела, но откатываться некуда (нет предыдущего образа)."
                        }
                    } else {
                        echo "=> ✅ Релиз успешен! Производительность в норме."
                    }
                }
            }
        }
    }

    post {
        always {
            echo "=> Зачистка секретов из воркспейса..."
            sh 'rm -f cert.json ca.crt client.crt client.key reg_user reg_pass'
        }
    }
}