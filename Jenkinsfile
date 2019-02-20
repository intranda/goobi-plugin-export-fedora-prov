    pipeline {
      agent {
        docker {
          image 'maven:3-jdk-8'
          args '-v $HOME/.m2/root/.m2'
        }
      }

      stages {
        stage('build') {
          steps {
            sh 'mvn -f goobi-plugin-export-fedora-prov/pom.xml install'
          }
        }
      }
      post {
        success {
          archiveArtifacts artifacts: 'goobi-plugin-export-fedora-prov/target/*.jar, goobi-plugin-export-fedora-prov/*.xml', fingerprint:
          true
        }
      }
    }
