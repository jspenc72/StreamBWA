#!/usr/bin/python
#****************************************/
#	Script:		runWithChunker.py	
#	Author:		Hamid Mushtaq  			
#****************************************/
from xml.dom import minidom
import sys
import os
import time
import subprocess
import multiprocessing

USE_YARN_CLIENT_FOR_HADOOP = True

if len(sys.argv) < 3:
	print("Not enough arguments!")
	print("Example of usage: ./runPart.py config.xml chunkerConfig.xml")
	sys.exit(1)

exeName = "target/scala-2.11/streambwa_2.11-1.0.jar"
#exeName = "streambwa_2.11-1.0.jar"
chunkerExeName = "chunker_2.11-1.0.jar"
logFile = "time.txt"
configFilePath = sys.argv[1]
chunkerConfigFilePath = sys.argv[2]

if not os.path.isfile(configFilePath):
	print("Config file " + configFilePath + " does not exist!")
	sys.exit(1)
	
if not os.path.isfile(chunkerConfigFilePath):
	print("Chunker's config file " + chunkerConfigFilePath + " does not exist!")
	sys.exit(1)
	
doc = minidom.parse(configFilePath)
refPath = doc.getElementsByTagName("refPath")[0].firstChild.data
inputFolder = doc.getElementsByTagName("inputFolder")[0].firstChild.data
outputFolder = doc.getElementsByTagName("outputFolder")[0].firstChild.data
tmpFolder = doc.getElementsByTagName("tmpFolder")[0].firstChild.data
numInstances = doc.getElementsByTagName("numInstances")[0].firstChild.data
numTasks = doc.getElementsByTagName("numTasks")[0].firstChild.data
exe_mem = doc.getElementsByTagName("execMemGB")[0].firstChild.data + "g"
driver_mem = doc.getElementsByTagName("driverMemGB")[0].firstChild.data + "g"

doc = minidom.parse(chunkerConfigFilePath)
inputFileName = doc.getElementsByTagName("fastq1Path")[0].firstChild.data
fastq2Path = doc.getElementsByTagName("fastq2Path")[0].firstChild
inputFileName2 = "" if (fastq2Path == None) else fastq2Path.data
outputFolderChunker = doc.getElementsByTagName("outputFolder")[0].firstChild.data
driver_mem_chunker = doc.getElementsByTagName("driverMemGB")[0].firstChild.data + "g"

def executeStreamBWA():	
	if USE_YARN_CLIENT_FOR_HADOOP:
		os.system('cp ' + configFilePath + ' ./')
		if not os.path.exists(tmpFolder):
			os.makedirs(tmpFolder)
	
	diff_str = "yarn-client" if USE_YARN_CLIENT_FOR_HADOOP else ("yarn-cluster --files " + configFilePath + "," + dictPath)
	
	cmdStr = "$SPARK_HOME/bin/spark-submit " + \
	"--class \"StreamBWA\" --master " + diff_str + " " + \
	"--driver-memory " + driver_mem + " --executor-memory " + exe_mem + " " + \
	"--num-executors " + numInstances + " --executor-cores " + numTasks + " " + \
	exeName + " " + os.path.basename(configFilePath)
	
	print cmdStr
	addToLog("[" + time.ctime() + "] " + cmdStr)
	os.system(cmdStr)
	
	if USE_YARN_CLIENT_FOR_HADOOP:
		os.remove('./' + configFilePath[configFilePath.rfind('/') + 1:])
	
def executeChunker():
	cmdStr = "$SPARK_HOME/bin/spark-submit " + \
	"--class \"Chunker\" --master local[*] --driver-memory " + driver_mem_chunker + " " + chunkerExeName + " " + chunkerConfigFilePath
	
	print cmdStr
	os.system(cmdStr)
	
def addToLog(s):
	f = open(logFile,'a+')
	f.write(s + "\n")
	f.close() 

start_time = time.time()

addToLog("########################################\n[" + time.ctime() + "] Part1 started.")
if outputFolderChunker != inputFolder:
	print "The output folder of chunker: " + outputFolderChunker + ", is different than the input folder: " + inputFolder
	sys.exit(1)
	
# Remove the HDFS folders
os.system("hadoop fs -rm -r -f " + inputFolder)
os.system("hadoop fs -rm -r -f " + outputFolder)
# Start chunker
job1 = multiprocessing.Process(target=executeChunker)
job1.start()
# Start streamBWA
job2 = multiprocessing.Process(target=executeStreamBWA)
job2.start()
# Wait for both jobs to finish
job1.join()
job2.join()

addToLog("[" + time.ctime() + "]")
	
time_in_secs = int(time.time() - start_time)
mins = time_in_secs / 60
secs = time_in_secs % 60

print "|| Time taken = " + str(mins) + " mins " + str(secs) + " secs ||"