docker run -d \
    --name artifactory-test \
    -p 8081:8081 \
    -p 8082:8082 \
    releases-docker.jfrog.io/jfrog/artifactory-oss:7.77.3