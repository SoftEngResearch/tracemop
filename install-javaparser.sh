(
    cd /tmp
    git clone git@github.com:javaparser/javaparser.git
    (
        cd javaparser
        git co javaparser-parent-3.23.1
        sed -i 's/public final int hashCode/public int hashCode/' javaparser-core/src/main/java/com/github/javaparser/ast/Node.java
        mvn install -DskipTests -DskipITs
    )
)
