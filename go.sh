#!/bin/bash
JAR=/home/ubuntu/jjc/database/blazegraph-jar/target/blazegraph-jar-debug_off_2_0_1-SNAPSHOT.jar
/usr/lib/jvm/java-8-oracle/bin/java \
   -Djetty.start.timeout=600 -Dorg.eclipse.jetty.server.Request.maxFormContentSize=2000000000 \
   -XX:+UseG1GC -XX:+UseStringDeduplication -Xms2064800k -Xmx21329600k \
   -server -Dlog4j.configuration=log4j.properties \
   -cp $JAR com.bigdata.rdf.sail.webapp.NanoSparqlServer 9999 kb RWStore.properties
