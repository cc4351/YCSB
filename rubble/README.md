## To build rubble:
under the YCSB directory, run:  
```mvn -pl rubble -am clean install```

## To run the replicator:
under the YCSB directory, run: 
```mvn -pl rubble exec:java -Dexec.mainClass=site.ycsb.db.rubble.Replicator```

configuration file ```config.yml``` is under ```src/main/resources```

## To run the loading phase:
```sudo bash rubble/load.sh```