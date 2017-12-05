import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.feature.{Normalizer, StringIndexer, VectorAssembler, VectorIndexer}
import org.apache.spark.ml.regression.{RandomForestRegressionModel, RandomForestRegressor}
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.{DataFrame, SparkSession}

class FlightProcessor(spark: SparkSession, targetVariable: String){

  //Datasets
  import spark.implicits._
  var flight_dataframe_training : DataFrame = null
  var flight_dataframe_test : DataFrame = null
  var training_percentage: Double = 0.1
  var test_percentage: Double = 0.05
  //create some udf functions
  val toInt = udf[Int, String](_.toInt)
  val hhmmToMin = udf[Int, String](time => (time.toInt/100) * 60 + time.toInt % 100)


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

    this.test_percentage=test_percentage
    this.training_percentage=training_percentage
    val flights_df = spark.read
      .format("csv")
      .option("header", "true")
      .load(file)


    flight_dataframe_training = transformCSVFile(flights_df)
    flight_dataframe_training.na.drop()
    flight_dataframe_training.show(5,false)

    val Array(trainingData, testData) = flights_df.randomSplit(Array(training_percentage, test_percentage),seed)
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
      .drop("Year"
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

  }

  //Transforms the dataframe(types and indexes) for the machine learning algorithms and add features columns
  def transformDataframe(): Unit ={
    //Create Indexers
    val OriginIndx = new StringIndexer().setInputCol("Origin").setOutputCol("OriginIndx")
    val DestIndx = new StringIndexer().setInputCol("Dest").setOutputCol("DestIndx")
    val UniqueCarrierIndx = new StringIndexer().setInputCol("UniqueCarrier").setOutputCol("UniqueCarrierIndx")

    flight_dataframe_training = OriginIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)
    flight_dataframe_training = DestIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)
    flight_dataframe_training = UniqueCarrierIndx.fit(flight_dataframe_training).transform(flight_dataframe_training)




    //flight_dataframe_training.show(5,false)
    //df_with_features.show(truncate=false)*/
  }

  def RandomForest(): Unit ={


    //Add features column
    /*val assembler = new VectorAssembler()
      .setInputCols(Array("Month","DayOfMonth","DayOfWeek","DepTime","CRSDepTime","CRSArrTime","UniqueCarrierIndx","CRSElapsedTime","DepDelay","OriginIndx","DestIndx","Distance","TaxiOut"))
      .setOutputCol("features")*/


    /*val assembler = new VectorAssembler()
      .setInputCols(Array("Month","DayOfMonth","DayOfWeek","CRSDepTime","CRSArrTime"))
      .setOutputCol("features")*/
    val assembler = new VectorAssembler()
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
    println("Learned regression forest model:\n" + rfModel.toDebugString)


  }

  def GradientBoostTree(): Unit ={

  }

  def LogisticRegression_Binominal: Unit ={

  }

  def LogisticRegression_Multinominal: Unit ={

  }
}
