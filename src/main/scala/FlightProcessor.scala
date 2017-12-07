
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.RandomForest
import org.apache.log4j.{Level, Logger}
import org.apache.spark.mllib.evaluation.RegressionMetrics
import org.apache.spark.sql.functions.stddev_pop
import org.apache.spark.sql.functions.avg
import org.apache.spark.mllib.stat.Statistics

class FlightProcessor(spark: SparkSession, targetVariable: String){

  //Datasets
  var flight_dataframe_training : DataFrame = null
  var flight_dataframe_test : DataFrame = null
  var training_percentage: Double = 0.7
  var test_percentage: Double = 0.3
  //create some udf functions
  val toInt = udf[Int, String](_.toInt)
  val hhmmToMin = udf[Int, String](time => (time.toInt/100)/* * 60 + time.toInt % 100*/)


  //Load all data from CSV file and creates the dataframes for training and testing
  def load(training_path : String, test_path:String): Unit ={

    //Load training data
    val flights_df_train = spark.read
      .format("csv")
      .option("header", "true")
      .load(training_path)
    flight_dataframe_training = transformCSVFile(flights_df_train)

    //Load testing data
    val flights_df_test = spark.read
      .format("csv")
      .option("header", "true")
      .load(test_path)
    flight_dataframe_test = transformCSVFile(flights_df_test)
  }

  def load(file :String, training_percentage :Double,test_percentage :Double, seed :Long): Unit ={

    //
    this.test_percentage=test_percentage
    this.training_percentage=training_percentage

    val flights_df = spark.read
      .format("csv")
      .option("header", "true")
      .load(file)


    flight_dataframe_training = transformCSVFile(flights_df)
    flight_dataframe_training.na.drop()
    flight_dataframe_training.show(5,false)

    //val Array(trainingData, testData) = flights_df.randomSplit(Array(training_percentage, test_percentage),seed)
   // flight_dataframe_test = transformCSVFile(flights_df)
  }

  //Parses the columns and gives them all the necessary arragements of datatype conversion
  private def transformCSVFile(df: DataFrame): DataFrame ={

    df
      .filter(df("Cancelled").equalTo(0))
      .filter(df("DepDelay").notEqual(("NA")))
      .filter(df("DepTime").notEqual(("NA")))
      .filter(df("TaxiOut").notEqual(("NA")))
      .filter(df("ArrDelay").notEqual(("NA")))
      .filter(df("CRSElapsedTime").notEqual(("NA")))
      .withColumn("Month", toInt(df("Month")))
      .withColumn("DayOfMonth", toInt(df("DayOfMonth")))
      .withColumn("DayOfWeek", toInt(df("DayOfWeek")))
      .withColumn("DepTime", hhmmToMin(df("DepTime")))
      .withColumn("CRSDepTime", hhmmToMin(df("CRSDepTime")))
      .withColumn("CRSArrTime", hhmmToMin(df("CRSArrTime")))
      .withColumn("CRSElapsedTime", toInt(df("CRSElapsedTime")))
      .withColumn("ArrDelay", toInt(df("ArrDelay")))
      .withColumn("DepDelay", toInt(df("DepDelay")))
      .withColumn("Distance", toInt(df("Distance")))
      .withColumn("TaxiOut", toInt(df("TaxiOut")))
      .drop(
        "Year"
        ,"ArrTime"
        ,"FlightNum"
        ,"TailNum"
        ,"ActualElapsedTime"
        ,"AirTime"
        ,"TaxiIn"
        ,"Cancelled"
        ,"CancellationCode"
        ,"Diverted"
        ,"CarrierDelay"
        ,"WeatherDelay"
        ,"NASDelay"
        ,"SecurityDelay"
        ,"LateAircraftDelay")
      .limit(100000)



  }

  //Transforms the dataframe(types and indexes) for the machine learning algorithms and add features columns
  def transformDataframe(): Unit ={
    //Create Indexers
    /*println("TOTAL ROWS PRE: "+flight_dataframe_training.count())

    import org.apache.spark.sql.expressions.Window

    val w = Window.orderBy().
    dataset.withColumn("AMOUNT",
      when($"ID" === lag($"ID", 1).over(w), lag($"AMOUNT", 1).over(w)).otherwise($"AMOUNT")
    ).show*/


    /*flight_dataframe_training.createGlobalTempView("flight")

    println("STATING")
    val flights = flight_dataframe_training.select("TailNum").distinct().collect()
    val total_flights = flights.length
    val df1 :DataFrame = null
    var c = 1;

    flights.foreach(row => df1.union(spark.sql(s"select *, CASE WHEN @prev IS NULL then 0 WHEN @prev > 0 THEN 1 ELSE 0 END as previousWasDelayed, @prev as previous_ArrDelay, @prev := e.ArrDelay as current_ArrDelay from (select@prev := null) as i,flight as e WHERE TailNum = $row order by e.Year,e.Month,e.DayOfMonth,e.DepTime ASC"))
    )*/


    /*for(row <-  flights){
      println(s"STATUS: $c of $total_flights")
      c+=1
      df1.union(spark.sql(s"select *, CASE WHEN @prev IS NULL then 0 WHEN @prev > 0 THEN 1 ELSE 0 END as previousWasDelayed, @prev as previous_ArrDelay, @prev := e.ArrDelay as current_ArrDelay from (select@prev := null) as i,flight as e WHERE TailNum = $row order by e.Year,e.Month,e.DayOfMonth,e.DepTime ASC"))
    }*/
    //flight_dataframe_training = flight_dataframe_training.select("TailNum").distinct()(df1.union(spark.sql("SELECT * FROM where TailNum = $tailNumflight")))

    flight_dataframe_training.createOrReplaceTempView("flights")

    val df_tmp = spark.sql("select CRSDepTime as CRSDepTime_tmp , MEAN(ArrDelay) as MeanArrDelay from flights as Tmp GROUP BY CRSDepTime")
    flight_dataframe_training = flight_dataframe_training.join(df_tmp,flight_dataframe_training("CRSDepTime") === df_tmp("CRSDepTime_tmp")).drop("CRSDepTime_tmp")



    val MonthIndx = new StringIndexer().setInputCol("Month").setOutputCol("MonthIndx")
    val DayOfMonthIndx = new StringIndexer().setInputCol("DayOfMonth").setOutputCol("DayOfMonthIndx")
    val DayOfWeekIndx = new StringIndexer().setInputCol("DayOfWeek").setOutputCol("DayOfWeekIndx")
    val OriginIndx = new StringIndexer().setInputCol("Origin").setOutputCol("OriginIndx")
    val DestIndx = new StringIndexer().setInputCol("Dest").setOutputCol("DestIndx")
    val UniqueCarrierIndx = new StringIndexer().setInputCol("UniqueCarrier").setOutputCol("UniqueCarrierIndx")
    val CRSDepTimeIndx = new StringIndexer().setInputCol("CRSDepTime").setOutputCol("CRSDepTimeIndx")



    flight_dataframe_training = MonthIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)
    flight_dataframe_training = DayOfMonthIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)
    flight_dataframe_training = DayOfWeekIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)
    flight_dataframe_training = OriginIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)
    flight_dataframe_training = DestIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)
    flight_dataframe_training = UniqueCarrierIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)
    flight_dataframe_training = CRSDepTimeIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)






  }

  def RandomForest(): Unit ={

   /* val assembler = new VectorAssembler()
      .setInputCols(Array("Month","DayOfMonth","DayOfWeek","DepTime","CRSDepTime","CRSArrTime","UniqueCarrierIndx","CRSElapsedTime","DepDelay","OriginIndx","DestIndx","Distance","TaxiOut"))
      .setOutputCol("features")

    val featureIndexer = new VectorIndexer()
      .setInputCol("features")
      .setOutputCol("indexedFeatures")
      .setMaxCategories(32)

    val normalizer = new Normalizer()
      .setInputCol("indexedFeatures")
      .setOutputCol("normFeatures")
      .setP(1.0)



    // Split the data into training and test sets (30% held out for testing).
    val Array(trainingData, testData) = flight_dataframe_training.randomSplit(Array(training_percentage, test_percentage))


    // Train a RandomForest model.
    val rf = new RandomForestRegressor()
      .setLabelCol(targetVariable)
      .setFeaturesCol("normFeatures")
      .setMaxBins(2700)
      .setImpurity("variance")
      .setFeatureSubsetStrategy("auto")
      .setMaxDepth(9)


    // Chain indexer and forest in a Pipeline.
    val pipeline = new Pipeline()
      .setStages(Array(assembler,featureIndexer, normalizer,rf))

    // Train model. This also runs the indexer.
    val model = pipeline.fit(trainingData)

    // Make predictions.
    val predictions = model.transform(testData)

    // Select example rows to display.
    predictions.show(5,false)

    // Select (prediction, true label) and compute test error.
    val evaluator = new RegressionEvaluator()
      .setLabelCol(targetVariable)
      .setPredictionCol("prediction")
      .setMetricName("rmse")

    val rmse = evaluator.evaluate(predictions)
    println("Root Mean Squared Error (RMSE) on test data = " + rmse)

    predictions.write.option("header", "true").csv("/home/danielreis/Desktop/bigdata/res.csv")
    val rfModel = model.stages(1).asInstanceOf[RandomForestRegressionModel]
    println("Learned regression forest model:\n" + rfModel.toDebugString)*/
  }

  def RandomForest2(): Unit ={

    /*val assembler = new VectorAssembler()
      .setInputCols(Array(
        "MonthIndx",
        "DayOfMonthIndx",
        "DayOfWeekIndx",
        "CRSDepTime",
        "CRSArrTime",
        "UniqueCarrierIndx",
        "CRSElapsedTime",
        "DepDelay",
        "OriginIndx",
        "DestIndx",
        "Distance",
        "TaxiOut",
        "MeanArrDelay",
        "CRSDepTimeIndx"
      ))
      .setOutputCol("features")

      flight_dataframe_training = assembler.transform(flight_dataframe_training)*/



    flight_dataframe_training.printSchema()

    //flight_dataframe_training.show(5,false)
    /*val normalizer = new Normalizer()
      .setInputCol("indexedFeatures")
      .setOutputCol("normFeatures")
      .setP(1.0)

    flight_dataframe_training = normalizer.transform(flight_dataframe_training)*/

    //val MonthIndx = flight_dataframe_training.select("MonthIndx").distinct().count().toInt
    //val DayOfMonthIndx = flight_dataframe_training.select("DayOfMonthIndx").distinct().count().toInt
    //val DayOfWeekIndx = flight_dataframe_training.select("DayOfWeekIndx").distinct().count().toInt
    val numUniqueCarrierIndx = flight_dataframe_training.select("UniqueCarrierIndx").distinct().count().toInt
    val numOriginIndx = flight_dataframe_training.select("OriginIndx").distinct().count().toInt
    val numDestIndx = flight_dataframe_training.select("DestIndx").distinct().count().toInt
    val CRSDepTimeIndx = flight_dataframe_training.select("CRSDepTimeIndx").distinct().count().toInt
    flight_dataframe_training.show(5,false)




    /*val labeled = flight_dataframe_training.rdd.map(row =>
      if(row.getAs[Any]("features").isInstanceOf[org.apache.spark.ml.linalg.DenseVector])
        LabeledPoint(row.getAs[Integer]("ArrDelay").toDouble,Vectors.dense(row.getAs[org.apache.spark.ml.linalg.DenseVector]("features").values))
      else
        LabeledPoint(row.getAs[Integer]("ArrDelay").toDouble,Vectors.dense(row.getAs[org.apache.spark.ml.linalg.SparseVector]("features").toDense.values)))*/


    flight_dataframe_training.printSchema()

    val labeled = flight_dataframe_training.rdd.map(row => LabeledPoint(row.getAs[Integer]("ArrDelay").toDouble,
      Vectors.dense(Array(
        row.getAs[Double]("MonthIndx"),
        row.getAs[Double]("DayOfMonthIndx"),
        row.getAs[Double]("DayOfWeekIndx"),
        row.getAs[Integer]("CRSElapsedTime").toDouble,
        row.getAs[Integer]("DepDelay").toDouble,
        row.getAs[Double]("UniqueCarrierIndx"),
        row.getAs[Double]("OriginIndx"),
        row.getAs[Double]("DestIndx"),
        row.getAs[Double]("CRSDepTimeIndx"),
        row.getAs[Double]("MeanArrDelay"),
        row.getAs[Integer]("Distance").toDouble,
        row.getAs[Integer]("TaxiOut").toDouble
      ))))

    println(labeled.first().features)


    //labeled.map(lp => lp.features)
    // Split the data into training and test sets (30% held out for testing).
    val Array(trainingData, testData) = labeled.randomSplit(Array(0.7, 0.3))


    //Categories:
    //Month,DayOfMonth,DayOfWeek,UniqueCarrierIndx,OriginIndx,DestIndx

    // Train a RandomForest model.
    // Empty categoricalFeaturesInfo indicates all features are continuous.
    val categoricalFeaturesInfo = Map[Int, Int]((0,12/*MonthIndx*/),(1,31/*DayOfMonthIndx*/),(2,7/*DayOfWeekIndx*/),(5,numUniqueCarrierIndx),(6,numOriginIndx),(7,numDestIndx),(8,CRSDepTimeIndx))
    val numTrees = 10 // Use more in practice.
    val featureSubsetStrategy = "auto" // Let the algorithm choose.
    val impurity = "variance"
    val maxDepth = 9
    val maxBins = 2700


    val model = org.apache.spark.mllib.tree.RandomForest.trainRegressor(trainingData,categoricalFeaturesInfo,numTrees,featureSubsetStrategy,impurity,maxDepth,maxBins)

    // Evaluate model on test instances and compute test error
    val labelAndPreds = testData.map { point =>
      val prediction = model.predict(point.features)
      (point.label, prediction)
    }
    val testErr
    = labelAndPreds.filter(r => r._1 != r._2).count.toDouble / testData.count()
    println("Test Error = " + testErr)

    // Instantiate metrics object
    val metrics = new RegressionMetrics(labelAndPreds)

    /// Squared error
    println(s"MSE = ${metrics.meanSquaredError}")
    println(s"RMSE = ${metrics.rootMeanSquaredError}")

    // R-squared
    println(s"R-squared = ${metrics.r2}")

    // Mean absolute error
    println(s"MAE = ${metrics.meanAbsoluteError}")

    // Explained variance
    println(s"Explained variance = ${metrics.explainedVariance}")

    //println("Learned classification forest model:\n" + model.toDebugString)
  }

}
