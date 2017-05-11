/*
 * Copyright (C) 2016-2017 Hamid Mushtaq
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.scheduler._
import sys.process._
import org.apache.log4j.Logger
import org.apache.log4j.Level
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.WildcardFileFilter

import java.io._
import java.nio.file.{Paths, Files}
import java.net._
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar

import scala.sys.process.Process
import scala.io.Source
import scala.collection.JavaConversions._
import scala.collection.mutable._
import scala.util.Sorting._
import scala.concurrent.Future
import scala.concurrent.forkjoin._
import scala.collection.parallel.ForkJoinTaskSupport
import scala.util.Random

import tudelft.utils._
import utils._

import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.spark.storage.StorageLevel._
import org.apache.spark.HashPartitioner

import htsjdk.samtools.util.BufferedLineReader
import htsjdk.samtools._

object StreamBWA
{
val compressRDDs = true
//////////////////////////////////////////////////////////////////////////////
def bwaRun (chunkName: String, config: Configuration) : Int =
{
	val downloadNeededFiles = config.getDownloadRef.toBoolean
	val streaming = config.getStreaming.toBoolean
	val x = chunkName.replace(".gz", "")
	var inputFileName = config.getInputFolder + x + ".gz" 
	val fqFileName = config.getTmpFolder + x
	val hdfsManager = new HDFSManager
	var r = 0
	
	var t0 = System.currentTimeMillis
	
	hdfsManager.create(config.getOutputFolder + "log/" + x)
	DownloadManager.downloadBinProgram("bwa", config)
	val file = new File(FilesManager.getBinToolsDirPath(config) + "bwa") 
	file.setExecutable(true)
	if (downloadNeededFiles)
	{
		LogWriter.dbgLog(x, t0, "download\tDownloading reference files for bwa", config)
		if (DownloadManager.downloadBWAFiles(x, config) != 0)
			return 1
	}
	
	LogWriter.dbgLog(x, t0, "->\tchunkName = " + chunkName + ", x = " + x + ", inputFileName = " + inputFileName, config)
	
	if (!Files.exists(Paths.get(FilesManager.getRefFilePath(config))))
	{
		LogWriter.dbgLog(x, t0, "#\tReference file " + FilesManager.getRefFilePath(config) + " not found on this node!", config)
		return 1
	}
	
	if (streaming)
	{
		val fileID = x.replace(".fq", "").toInt
		while(!hdfsManager.exists(config.getInputFolder + "ulStatus/" + fileID))
		{
			if (hdfsManager.exists(config.getInputFolder + "ulStatus/end.txt"))
			{
				if (!hdfsManager.exists(config.getInputFolder + "ulStatus/" + fileID))
				{
					LogWriter.dbgLog(x, t0, "#\tfileID = " + fileID + ", end.txt exists but this file doesn't!", config)
					return 1
				}
			}
			Thread.sleep(1000)
		}
	}
	
	var inputFileBytes: Array[Byte] = null
	inputFileBytes = hdfsManager.readBytes(inputFileName)
	LogWriter.dbgLog(x, t0, "0a\tSize of " + inputFileName + " = " + (inputFileBytes.size / (1024 * 1024)) + " MB.", config)
	
	val decompressedStr = new GzipDecompressor(inputFileBytes).decompress
	new PrintWriter(fqFileName) {write(decompressedStr); close}
	LogWriter.dbgLog(x, t0, "0b\tFastq file size = " + ((new File(fqFileName).length) / (1024 * 1024)) + " MB", config)
	
	// bwa mem input_files_directory/fasta_file.fasta -p -t 2 x.fq > out_file
	val progName = FilesManager.getBinToolsDirPath(config) + "bwa mem "
	val command_str = progName + FilesManager.getRefFilePath(config) + " " + config.getExtraBWAParams + " -t " + config.getNumThreads + " " + fqFileName
	
	LogWriter.dbgLog(x, t0, "1\tbwa mem started: " + command_str, config)
	val bwaOutStr = new StringBuilder((inputFileBytes.size * 1.25).toInt)
	val logger = ProcessLogger(
		(o: String) => {bwaOutStr.append(o + '\n')}
		,
		(e: String) => {} // do nothing
	)
	command_str ! logger;
	
	new File(fqFileName).delete()
	
	hdfsManager.writeWholeFile(config.getOutputFolder + x.replace(".fq", ".sam"), bwaOutStr.toString)
	
	LogWriter.dbgLog(x, t0, "2\t" + "Content uploaded to the HDFS, r = " + r, config)
	return r
}

def main(args: Array[String]) 
{
	val config = new Configuration()
	config.initialize(args(0))
	val conf = new SparkConf().setAppName("StreamBWA")
	
	if (compressRDDs)
		conf.set("spark.rdd.compress","true")
	
	val sc = new SparkContext(conf)
	val bcConfig = sc.broadcast(config)
	val hdfsManager = new HDFSManager
	
	// Comment these two lines if you want to see more verbose messages from Spark
	//Logger.getLogger("org").setLevel(Level.OFF);
	//Logger.getLogger("akka").setLevel(Level.OFF);
	
	config.print() 
	
	if (!hdfsManager.exists("sparkLog.txt"))
		hdfsManager.create("sparkLog.txt")
	if (!hdfsManager.exists("errorLog.txt"))
		hdfsManager.create("errorLog.txt")
	
	var t0 = System.currentTimeMillis
	//////////////////////////////////////////////////////////////////////////
	val streaming = config.getStreaming.toBoolean
	if (!streaming)
	{
		val inputFileNames = FilesManager.getInputFileNames(config.getInputFolder, config).filter(x => x.contains(".fq"))  
		if (inputFileNames == null)
		{
			println("The input directory " + config.getInputFolder() + " does not exist!")
			System.exit(1)
		}
		inputFileNames.foreach(println)
		
		// Give chunks to bwa instances
		val inputData = sc.parallelize(inputFileNames, inputFileNames.size) 
		inputData.foreach(x => bwaRun(x, bcConfig.value))
	}
	else
	{
		var done = false
		val parTasks = config.getGroupSize.toInt
		var si = 0
		var ei = parTasks
		while(!done)
		{
			var indexes = (si until ei).toArray  
			val inputData = sc.parallelize(indexes, indexes.size)
			val r = inputData.map(x => bwaRun(x + ".fq.gz", bcConfig.value))
			val finished = r.filter(_ == 1)
			if (finished.count > 0)
				done = true
			si += parTasks
			ei += parTasks
		}
	}
	//////////////////////////////////////////////////////////////////////
	var et = (System.currentTimeMillis - t0) / 1000
	LogWriter.statusLog("Execution time:", t0, et.toString() + "\tsecs", config)
}
//////////////////////////////////////////////////////////////////////////////
} // End of Class definition
