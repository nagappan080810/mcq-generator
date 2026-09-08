curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk list java | grep -i graal
sdk install java 21.0.2-graalce
# graalvm 25.3.4+1.r25-graalce is the latest version of graalvm-ce-java17-linux-amd64-25.3.4.tar.gz
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.3.4+1.r25-graalce
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.3.4+1.r25-graalce" GRAALVM_HOME="$JAVA_HOME"
./mvnw -Pnative -DskipTests clean native:compile