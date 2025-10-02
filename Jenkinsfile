pipeline {
  agent none
  options { timestamps(); disableConcurrentBuilds() }  // 동시 실행 방지

  stages {
    stage('Checkout') {
      agent any
      steps { checkout scm }
    }


    // 도커/컴포즈 명령은 docker CLI 컨테이너에서 실행 (호스트의 docker.sock 사용)
    stage('Deploy backend') {
      agent {
        docker {
          image 'docker:27.1.1-cli'        // docker + compose 포함
          args  "-v /var/run/docker.sock:/var/run/docker.sock
        }
      }
      steps {
          withCredentials([
            string(credentialsId: 'vault-token', variable: 'VAULT_TOKEN'),
            string(credentialsId: 'vault-addr',  variable: 'VAULT_ADDR')
          ]) {
            sh '''
              set -eu
              cd backend
              # compose가 위 환경변수를 그대로 컨테이너에 전달 → application.yml에서 ${...}로 읽음
              docker compose -p backend -f compose.yml up -d --build app
            '''
          }
        }
    }

    // 필요 시: monitoring 변경시에만 갱신
    // stage('Deploy monitoring (if changed)') {
    //   when { changeset pattern: 'monitoring/**', comparator: 'ANT' }
    //   agent {
    //     docker {
    //       image 'docker:27.1.1-cli'
    //       args  "-v /var/run/docker.sock:/var/run/docker.sock -v $WORKSPACE:$WORKSPACE -w $WORKSPACE"
    //     }
    //   }
    //   steps {
    //     sh 'cd monitoring && docker compose -p mon -f compose.yml up -d'
    //   }
    // }

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
    success { echo "✅ Deployed backend on main" }
    failure { echo "❌ Deployment failed" }
  }
}
