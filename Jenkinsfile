pipeline {
  agent none
  options { timestamps(); disableConcurrentBuilds() }  // 동시 실행 방지

  stages {
    stage('Checkout') {
      agent any
      steps { checkout scm }
    }

    stage('Build JAR (Gradle/Maven 자동감지)') {
      // JDK만 있으면 gradlew/mvnw로 빌드 가능
      agent {
        docker {
          image 'eclipse-temurin:17-jdk'   // JDK 17
          args  "-v $WORKSPACE:$WORKSPACE -w $WORKSPACE"
          reuseNode true
        }
      }
      steps {
        sh '''
          set -eux
          # Gradle 또는 Maven wrapper 자동 감지
          if [ -f ./gradlew ]; then
            chmod +x ./gradlew
            ./gradlew clean bootJar -x test
            JAR_SRC=$(ls build/libs/*.jar | head -n1)
          elif [ -f ./mvnw ]; then
            chmod +x ./mvnw
            ./mvnw -B -DskipTests clean package
            JAR_SRC=$(ls target/*.jar | head -n1)
          else
            echo "❌ gradlew/mvnw가 없습니다. wrapper를 추가해주세요."
            exit 1
          fi

          # Docker build context가 바라보는 위치에 최신 JAR 복사
          mkdir -p backend/app
          cp -f "$JAR_SRC" backend/app/app.jar

          # 디버그용 출력
          echo "Using JAR: backend/app/app.jar"
          ls -l backend/app
        '''
      }
    }

    stage('Deploy backend (Compose from WORKSPACE)') {
      // docker CLI 컨테이너에서 호스트의 docker.sock 사용
      agent {
        docker {
          image 'docker:27.1.1-cli'
          args  "--entrypoint='' -v /var/run/docker.sock:/var/run/docker.sock --group-add 988 \
                 -e HOME=/var/jenkins_home -e DOCKER_CONFIG=/var/jenkins_home/.docker \
                 -v $WORKSPACE:$WORKSPACE -w $WORKSPACE/backend"
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

            # 베이스 이미지 최신화(옵션): --pull always
            docker compose -p backend -f compose.yml build --pull always app

            # 최신 이미지로 컨테이너 재기동
            docker compose -p backend -f compose.yml up -d app
          '''
        }
      }
    }

    stage('Health check') {
      agent any
      steps {
        sh '''
          set +e
          echo "App:"        && curl -sS http://127.0.0.1:8080/actuator/health || true
          echo "Prometheus:" && curl -sS http://127.0.0.1:9090/-/ready         || true
          echo "Loki:"       && curl -sS http://127.0.0.1:3100/ready            || true
          echo "Grafana:"    && curl -sS http://127.0.0.1:3000/api/health       || true
          echo "Jenkins:"    && curl -sSI http://127.0.0.1:8081/login | head -n1 || true
        '''
      }
    }
  }

  post {
    success { echo "✅ Deployed backend with latest code" }
    failure { echo "❌ Deployment failed" }
  }
}
