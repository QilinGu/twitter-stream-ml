package com.giorgioinf.twtml.spark

import com.giorgioinf.twtml.web.WebClient
import com.typesafe.config.ConfigFactory
import org.apache.spark.{Logging, SparkConf}
import org.apache.spark.mllib.feature.{IDF, HashingTF, Normalizer}
import org.apache.spark.mllib.linalg.{SparseVector, DenseVector, Vector, Vectors}
import org.apache.spark.mllib.regression.{LabeledPoint, StreamingLinearRegressionWithSGD}
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.twitter.TwitterUtils
import org.viz.lightning.Lightning
import scala.concurrent.duration._
import scala.util.Try
import twitter4j.Status

object LinearRegression extends Logging {

  val name = "twitter-stream-ml-linear-regression"
  val numTextFeatures = 10000
  val numNumberFeatures = 4
  val numFeatures = numTextFeatures + numNumberFeatures
  val numberFeatureIndices = (numTextFeatures to numFeatures-1).toArray
  val hashText = new HashingTF(numTextFeatures)
  val normalizer = new Normalizer()

  /**
   * Create feature vectors by turning each tweet into bigrams of
   * characters (an n-gram model) and then hashing those to a
   * length-1000 feature vector that we can pass to MLlib.
   * This is a common way to decrease the number of features in a
   * model while still getting excellent accuracy (otherwise every
   * pair of Unicode characters would potentially be a feature).
   */
  def featurizeText(statuses: Status): SparseVector = {
    val text = statuses.getRetweetedStatus.getText
    hashText.transform(text.sliding(2).toSeq)
    //hashText.transform(text.split("\\s+"))
      .asInstanceOf[SparseVector]
  }

  def featurizeNumbers(statuses: Status): Vector = {
    val user = statuses.getRetweetedStatus.getUser
    val created = statuses.getRetweetedStatus.getCreatedAt
    val timeLeft = (System.currentTimeMillis - created.getTime)
    val vector = Vectors.dense(
      user.getFollowersCount * Math.pow(10, -10),
      user.getFavouritesCount * Math.pow(10, -10),
      user.getFriendsCount * Math.pow(10, -10),
      timeLeft * Math.pow(10, -14)
      //retweeted.getURLEntities.length,
      //retweeted.getUserMentionEntities.length
    )
    //normalizer.transform(vector
    vector
  }

  def featurize(statuses: Status): LabeledPoint = {
    val textFeatures = featurizeText(statuses)
    val numberFeatures = featurizeNumbers(statuses)
    val features = Vectors.sparse(
      numFeatures,
      textFeatures.indices ++ numberFeatureIndices,
      textFeatures.values ++ numberFeatures.toArray
    )
    LabeledPoint( statuses.getRetweetedStatus.getRetweetCount.toDouble, features )
  }

  def filtrate(statuses: Status): Boolean = {
    (
      statuses.getLang == "en" &&
      statuses.isRetweet &&
      retweetInterval(statuses, 10, 100)
      //retweetHoursLeft(statuses) == 1
      //
    )
  }



  def retweetInterval(statuses: Status, start:Long, end:Long):Boolean = {
    val n = statuses.getRetweetedStatus.getRetweetCount
    (n >= start && n <= end)
  }

  def main(args: Array[String]) {

    log.info("Loading application config...")

    val conf = ConfigFactory.load
    val lgn = Lightning(conf.getString("lightning"))
    val web = WebClient(conf.getString("twtweb"))

    log.info("Initializing Lightning graph session...")
    lgn.createSession(name)

    val realColor = Array(135,206,235)
    val predColor = Array(0,0,139)
    val graph = lgn.linestreaming(
        Array(Array(0.0), Array(0.0)),
        size=Array(14, 4),
        color=Array(realColor, predColor))

    Try(web.config(lgn.session, lgn.host, List(graph.id)))

    log.info("Initializing Spark Machine Learning Model...")

    val initialWeights = Vectors.dense(Array.fill(numFeatures)(1.0))
    val model = new StreamingLinearRegressionWithSGD()
      //.setNumIterations(200)
      //.setStepSize(0.0001)
      //.setMiniBatchFraction(1.0)
      .setInitialWeights(initialWeights)

    log.info("Initializing Streaming Spark Context...")

    val sparkConf = new SparkConf().setAppName(name)
    val ssc = new StreamingContext(sparkConf, Seconds(10))

    val lbZero = LabeledPoint(0.0, Vectors.sparse(numFeatures, Array(), Array()))
    val rddZero = ssc.sparkContext.parallelize(Array(lbZero))

    log.info("Initializing Twitter stream...")

    val stream = TwitterUtils.createStream(ssc, None)
      .filter(filtrate)
      .map(featurize)
      // training zero stream(0) problems
      .transform(rdd => (if (rdd.count == 0) rddZero else rdd))
      .cache()

    model.trainOn(stream)

    var count = 0L
    stream.foreachRDD({ rdd =>
      if (!rdd.isEmpty) {
        count += rdd.count
        val nonZeroRdd = rdd.filter(_.label > 0)
        val real = nonZeroRdd.map(_.label)
        val pred = model.latestModel.predict(nonZeroRdd.map(_.features))

        val realStdev = real.stdev
        val predStdev = pred.stdev
        if (log.isDebugEnabled) {
          log.debug("stdev: {} => {}", realStdev, predStdev)
        }




        Try(web.stats(count))
        Try(graph.append( Array(Array(realStdev), Array(predStdev) )))
        //Try(graph.append( Array(pred.toArray, real.toArray )))
      }
    })




    // Start the streaming computation
    ssc.start()
    log.info("Initialization complete.")
    ssc.awaitTermination()
  }

}