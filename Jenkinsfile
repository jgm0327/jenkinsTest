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
      agent any   // ← docker CLI 컨테이너 필요 없음
      steps {
        sh '''
          set -eux
          for i in $(seq 1 60); do
            if curl -fsS "http://127.0.0.1:${NEW_PORT}/actuator/health" | grep -q '"UP"'; then
              echo "Health OK on ${NEW_PORT}"
              exit 0
            fi
            echo "waiting app on ${NEW_PORT} ..."
            sleep 2
          done
          echo "Health timeout on ${NEW_PORT}"
          exit 1
        '''
      }
    }

    stage('Switch Nginx to NEW color') {
      // 호스트 Nginx에 접근해야 하므로 agent any (컨테이너 X)
      agent any
      steps {
        sh '''
          set -eux
          echo "Switching to ${NEW_COLOR} (${NEW_PORT})"

          # 업스트림 한 줄 파일 교체 (sudoers에 NOPASSWD 필요)
          echo "set \\$app_upstream http://127.0.0.1:${NEW_PORT};" | sudo tee /etc/nginx/conf.d/app-upstream.conf >/dev/null

          sudo nginx -t
          sudo nginx -s reload

          # 현재 활성 색 기록
          echo "${NEW_COLOR}" | sudo tee /var/run/app_active_color >/dev/null
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
