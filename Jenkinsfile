pipeline {
  agent none
  options { timestamps(); disableConcurrentBuilds() }  // 동시 실행 방지

  environment {
    STACK_DIR   = '/home/ubuntu/monitoring-stack'
    APP_DIR     = "${STACK_DIR}/app"                          // ✅ Dockerfile COPY 기준 경로
    COMPOSE_YML = "${STACK_DIR}/backend/compose.yml"
  }

  stages {
    stage('Checkout') {
      agent any
      steps { checkout scm }
    }

    stage('Decide colors (blue/green)') {
      agent any
      steps {
        script {
          // 현재 활성 색 판단: 파일 > nginx conf > 기본 blue
          def current = sh(returnStdout: true, script: '''
            set -e
            if [ -f /var/run/app_active_color ]; then
              cat /var/run/app_active_color
            elif grep -q "18081" /etc/nginx/conf.d/app-upstream.conf 2>/dev/null; then
              echo green
            else
              echo blue
            fi
          ''').trim()
          env.CURRENT_COLOR = current
          env.NEW_COLOR     = (current == 'blue') ? 'green' : 'blue'
          env.NEW_PORT      = (env.NEW_COLOR == 'blue') ? '18080' : '18081'
          env.OLD_PORT      = (env.NEW_COLOR == 'blue') ? '18081' : '18080'
          echo "CURRENT=${env.CURRENT_COLOR} -> NEW=${env.NEW_COLOR} (port ${env.NEW_PORT})"
        }
      }
    }

    stage('Build Jar') {
      agent {
        docker {
          image 'eclipse-temurin:21-jdk'   // JDK 포함 컨테이너
          reuseNode true
        }
      }
      steps {
        sh '''
          set -eux
          chmod +x ./gradlew
          ./gradlew clean bootJar -x test

          # 생성된 JAR 찾기
          JAR=$(ls build/libs/*.jar | head -n1)

          # ✅ Dockerfile의 COPY app.jar가 동작하려면 app/ 아래에 있어야 함
          TARGET_DIR="$APP_DIR"
          mkdir -p "$TARGET_DIR"
          rm -f "$TARGET_DIR/app.jar" || true
          cp -f "$JAR" "$TARGET_DIR/app.jar"

          echo "[DEBUG] Copied jar:"
          ls -l "$TARGET_DIR"
        '''
      }
    }

    stage('Bring up NEW color (compose up --build)') {
      agent {
        docker {
          image 'docker:27.1.1-cli'        // docker + compose 포함
          args  "--entrypoint='' -v /var/run/docker.sock:/var/run/docker.sock --group-add 988 \
                 -e HOME=/var/jenkins_home -e DOCKER_CONFIG=/var/jenkins_home/.docker \
                 -v ${STACK_DIR}:${STACK_DIR}:rw"
          reuseNode true
        }
      }
      environment {
        HOME = '/var/jenkins_home'
        DOCKER_CONFIG = '/var/jenkins_home/.docker'
      }
      steps {
        withCredentials([
          string(credentialsId: 'VAULT_TOKEN', variable: 'VAULT_TOKEN'),
          string(credentialsId: 'VAULT_ADDR',  variable: 'VAULT_ADDR')
        ]) {
          sh '''
            set -eux
            docker version
            docker compose version

            # 비활성 쪽(app-${NEW_COLOR})부터 빌드+기동
            docker compose -p backend -f "$COMPOSE_YML" up -d --build "app-${NEW_COLOR}"

            docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Image}}"
          '''
        }
      }
    }

    stage('Wait for health (NEW color)') {
      agent {
        docker {
          image 'docker:27.1.1-cli'
          args  "--entrypoint='' -v /var/run/docker.sock:/var/run/docker.sock --group-add 988"
          reuseNode true
        }
      }
      steps {
        sh '''
          set -eux
          for i in $(seq 1 60); do
            STATUS=$(docker inspect -f '{{.State.Health.Status}}' "app-${NEW_COLOR}" 2>/dev/null || echo "starting")
            [ "$STATUS" = "healthy" ] && break
            echo "health=${STATUS} ... waiting"
            sleep 2
            if [ $i -eq 60 ]; then
              echo "Health timeout"
              docker logs --tail 200 "app-${NEW_COLOR}" || true
              exit 1
            fi
          done
          # (보강) 내부 프로브
          docker exec "app-${NEW_COLOR}" sh -lc "wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '\"UP\"'"
        '''
      }
    }

    stage('Switch Nginx to NEW color') {
      agent {
        docker {
          image 'docker:27.1.1-cli'
          // 호스트 Nginx 설정/재시작을 위해 host PID, /etc/nginx, /run 마운트
          args  "--entrypoint='' -u 0 --pid=host \
                 -v /etc/nginx/conf.d:/host_nginx_conf \
                 -v /run:/host_run"
          reuseNode true
        }
      }
      steps {
        sh '''
          set -eux
    
          # 1) 활성 업스트림 파일 갱신 (호스트 경로로 직접 기록)
          printf "set \\$app_upstream http://127.0.0.1:%s;\\n" "${NEW_PORT}" > /host_nginx_conf/app-upstream.conf
    
          # 2) 호스트의 Nginx에 HUP 시그널 (reload)
          #    --pid=host 덕분에 호스트 PID를 볼 수 있음
          [ -f /host_run/nginx.pid ] || { echo "nginx.pid not found on host"; exit 1; }
          kill -HUP "$(cat /host_run/nginx.pid)"
    
          echo "Nginx switched to port ${NEW_PORT}"
        '''
      }
    }

    stage('Stop OLD color (optional)') {
      agent {
        docker {
          image 'docker:27.1.1-cli'
          args  "--entrypoint='' -v /var/run/docker.sock:/var/run/docker.sock"
          reuseNode true
        }
      }
      steps {
        sh '''
          set -eux
          docker compose -p backend -f "$COMPOSE_YML" stop "app-${CURRENT_COLOR}" || true
          docker rm -f "app-${CURRENT_COLOR}" || true
          docker image prune -f || true
        '''
      }
    }

    // (옵션) 전체 스택 상태 간단 점검
    stage('Health summary (optional)') {
      agent any
      steps {
        sh '''
          set +e
          echo "New App:"    && curl -sS http://127.0.0.1:${NEW_PORT}/actuator/health || true
          echo "Prometheus:" && curl -sS http://127.0.0.1:9090/-/ready              || true
          echo "Loki:"       && curl -sS http://127.0.0.1:3100/ready                 || true
          echo "Grafana:"    && curl -sS http://127.0.0.1:3000/api/health            || true
          echo "Jenkins:"    && curl -sSI http://127.0.0.1:8081/login | head -n1     || true
        '''
      }
    }
  }

  post {
    success { echo "✅ Blue-Green 배포 완료: now active = ${env.NEW_COLOR} (port ${env.NEW_PORT})" }
    failure { echo "❌ 배포 실패. 업스트림은 이전 상태일 수 있습니다. 필요시 수동 점검하세요." }
  }
}
