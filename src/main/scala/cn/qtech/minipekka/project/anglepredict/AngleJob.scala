package cn.qtech.minipekka.project.anglepredict

import cn.qtech.minipekka.common.datareader.SparkSqlReader
import cn.qtech.minipekka.project.anglepredict.AngleETL.writeRes2Kudu
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.storage.StorageLevel
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * author   :  jdi146
 * contact  :  jdi147.com@gmail.com
 * date     :  2021/3/31 22:34
 */


object AngleJob extends Serializable {


  val ss: SparkSession = SparkSession.builder().config(new SparkConf()).enableHiveSupport().getOrCreate()
  private val logger = LoggerFactory.getLogger(this.getClass)
  logger.info("-------------------------------------------- alg start ----------------------------------------------")


  /** ----------------------------------------------------- properties  start ---------------------------------------------------------- */
  val properties = new Properties()
  val propertiesStream = this.getClass.getClassLoader.getResourceAsStream("angle.properties")
  properties.load(propertiesStream)
  val kuduMaster: String = properties.getProperty("kuduMaster")
  val tableName: String = properties.getProperty("KuduTableName")
  val equipmentPath: String = properties.getProperty("equipmentMapPath")
  val stdModelPathPrefix: String = properties.getProperty("stdModelPathPrefix")
  val mcIDPath: String = properties.getProperty("mcIDPath")
  val resStoreTableName: String = properties.getProperty("resStoreTableName")
  val preRunDatePath: String = properties.getProperty("preRunDatePath")
  val mailToUsers: Seq[String] = properties.getProperty("mailToUsers").split(",")
  val mailFromUser: String = properties.getProperty("mailFromUser")
  val mailFromPwd: String = properties.getProperty("mailFromPwd")
  val mailPort: Int = properties.getProperty("mailPort").toInt
  val mailHost: String = properties.getProperty("mailHost")
  val mailSubject: String = properties.getProperty("mailSubject")
  /** ----------------------------------------------------- properties  end ---------------------------------------------------------- */


  /** ---------------------------------------------------- define/get file start ---------------------------------------------------- */
  /**
   * mcIDS, Array, ?????????, ??????????????????????????????????????????, ?????????????????????????????????
   * stdModelPathDict, Map??? ???????????????HDFS????????????
   * equipmentDF, ????????????????????????join??????, DF[?????????(String), ????????????(String), ???(String), ???(String)], ????????????????????????join
   */
  val mcIDs: Array[String] = SparkSqlReader.readByCsv(mcIDPath, ss = ss)
    .rdd.map(r => r.getAs[String]("mcID"))
    .collect()
    .map(mcid => mcid.replace(" ", ""))
    .distinct
  val stdModelPathDict: scala.collection.Map[String, String] = mcIDs
    .map(mcID => (mcID, stdModelPathPrefix + mcID + "_stdModel.csv"))
    .toMap
  val equipmentDF: DataFrame = SparkSqlReader.readByCsv(equipmentPath, ss)
  /** ---------------------------------------------------- define/get file end ---------------------------------------------------- */


  def main(args: Array[String]): Unit = {

    import ss.implicits._

    /** ----------------------------------------------------- preprocessing  start ---------------------------------------------------------- */
    /** getting datetime to filter date that has been computed, and record new datetime */
    //        val preDatetime = AngleETL.readPreRunDate(path = preRunDatePath, ss = ss) // ??????????????????????????????,?????????????????????????????????????????????????????????????????? ???????????????
    val preRunDate = "2020-05-20 08:00:00"
    println(s"--------------------------------------- preDatetime: $preRunDate-------------------------------------------")
    val filterStr = s"datetime > '$preRunDate' "
    val trainDF: DataFrame = AngleETL.getTrainData(kuduMaster = kuduMaster, tableName = tableName, ss = ss, filterStr = filterStr)
    val newDatetime: String = trainDF
      .select("datetime")
      .rdd.map(r => r.getAs[String]("datetime"))
      .reduce((str1, str2) => if (str1 > str2) str1 else str2)
    println(s"--------------------------------------- newDatetime: $newDatetime-------------------------------------------")
    val txtDF = ss.createDataFrame(
      Seq((newDatetime, 1))
    )
      .toDF("datetime", "tmp")
      .select("datetime")
//    AngleETL.writeDate2txt(txtDF, path = preRunDatePath, ss = ss)

    /** processedTrnRDD  RDD[(key(??????), (lineNmb, leadX, leadY, padX, padY), mcID??????)] */
    val processedTrnRDD = AngleETL
      .preprocessing(rawTrainData = trainDF, ss = ss)
      .persist(StorageLevel.MEMORY_AND_DISK)
    /** ----------------------------------------------------- preprocessing  end ----------------------------------------------------------- */


    /** ---------------------------------------------------------- train and predict start------------------------------------------------------ */
    val predictResults = mcIDs.map(mcID => {
      /** splitting data to predict */
      val trainData = processedTrnRDD
        .filter(_._3 == mcID)
        .map(r => (r._1, r._2))
      AngleETL.algRun(trainData, stdModelPathDict.getOrElse(mcID, "nan"), ss)
    })
    processedTrnRDD.unpersist()
    /** union all predicted result */
    val predictResDF = AngleETL.unionDataFrame(dataFrames = predictResults)
    predictResDF.show(128, truncate = false)
    /** ---------------------------------------------------------- train and predict end ------------------------------------------------------------ */


    /** ------------------------------------------------------------valid predictRes start ----------------------------------------------------------- */
    // ???????????????????????????
    //    val accList = predictResults.map(df => {
    //      df.show(5, truncate = false)
    //      val dfCount = df.count().toDouble
    //      val unqualifiedDF = df.filter($"code" === 1)
    //      val unqualifiedCount = unqualifiedDF.count().toDouble
    //      val acc = unqualifiedCount / dfCount
    //      val mcid = df.select("mcID").rdd.map(r => r.getAs[String]("mcID")).collect().head
    //      (mcid, unqualifiedCount, dfCount, acc)
    //    })
    //
    //    var sampleCount = accList.map(_._3).sum
    //    var unqualifiedCount = accList.map(_._2).sum
    //    var error = unqualifiedCount / sampleCount
    //    println(s"--------------------sampleCount:$sampleCount ---------------- error: $error  -----------unqualifiedCount:$unqualifiedCount--------------------------")
    //    accList.foreach(println)
    //
    //

    //    sampleCount = predictResDF.count()
    //    println(s"---------------------------------------- sample count: ???$sampleCount???----------------------------------------------")
    //
    //    val lackLineDF = predictResDF.filter($"code" === 2)
    //    val lackLineCount = lackLineDF.count()
    //    val lackLineRatio = lackLineCount.toDouble * 100 / sampleCount
    //    lackLineDF.show(lackLineCount.toInt, truncate = false)
    //    println(f"----------------------------------------- ???????????????$lackLineRatio %%???--------------------------------------------------")
    //
    //    val unqualified = predictResDF.filter($"code" === 1)
    //    unqualifiedCount = unqualified.count().toDouble
    //    val unqualifiedRatio = unqualifiedCount * 100 / sampleCount
    //    unqualified.show(256, truncate = false)
    //    println(f"----------------------------------------- ???????????????????????????$unqualifiedRatio %%???-------------------------------------------")
    //
    //    val qualified = predictResDF.filter($"code" === 0)
    //    val qualifiedCount = qualified.count()
    //    val qualifiedRatio = qualifiedCount.toDouble * 100 / sampleCount
    //    qualified.show(256, truncate = false)
    //    println(f"------------------------------------------- ???????????????$qualifiedRatio %%???-------------------------------------------------")
    /** ------------------------------------------------------- valid predictRes start end ----------------------------------------------------------- */


    /** ------------------------------------------------------------- after invoke start ------------------------------------------------------------ */
    /** writing predicted result to kudu and emailing to someone */
//    writeRes2Kudu(predictResDF, kuduMaster = kuduMaster, tableName = resStoreTableName, ss = ss)
    val toMailDF = AngleETL.predRes2Mail(predictResDF, ss = ss, equipmentDF = equipmentDF)
    toMailDF.show(128, truncate = false)
    val data = toMailDF.rdd.map(r => (r.getAs[String]("????????????"), r.getAs[String]("??????"),
      r.getAs[String]("????????????"), r.getAs[String]("??????"), r.getAs[String]("??????????????????"),
      r.getAs[String]("??????????????????")))
    AngleETL.sendMail(
      host = mailHost,
      port = mailPort,
      from = mailFromUser,
      fromPwd = mailFromPwd,
      to = mailToUsers,
      data = data,
      subject = mailSubject,
      attachmentName = "??????"
    )
    /** ------------------------------------------------------------------- after invoke end ------------------------------------------------------------ */

  }

}
