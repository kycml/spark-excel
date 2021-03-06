package com.crealytics.spark.excel

import java.io.File
import java.nio.file.Files
import java.sql.Timestamp

import org.scalacheck.{Arbitrary, Gen, Shrink}
import Arbitrary.{arbBigDecimal => _, arbLong => _, arbString => _, _}
import org.scalacheck.ScalacheckShapeless._
import org.scalatest.{FunSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions.lit

object IntegrationSuite {

  case class ExampleData(
    aBoolean: Boolean,
    aByte: Byte,
    aShort: Short,
    anInt: Int,
    aLong: Long,
    aDouble: Double,
    aBigDecimal: BigDecimal,
    aJavaBigDecimal: java.math.BigDecimal,
    aString: String,
    aTimestamp: java.sql.Timestamp,
    aDate: java.sql.Date
  )

  val exampleDataSchema = ScalaReflection.schemaFor[ExampleData].dataType.asInstanceOf[StructType]

  // inferring the schema will not match the original types exactly
  def inferredDataTypes(schema: StructType): Seq[Function[Seq[Any], DataType]] =
    schema.map(_.dataType).map { dt =>
      val pf: Function[Seq[Any], DataType] = dt match {
        case _: DecimalType => {
          case values: Seq[Any] if values.distinct == Seq("") => StringType
          case _ => DoubleType
        }
        case _: NumericType => {
          case _: Seq[Any] => DoubleType
        }
        case DateType => {
          case _: Seq[Any] => TimestampType
        }
        case t: DataType => {
          case _: Seq[Any] => t
        }
      }
      pf
    }

  def expectedDataTypes(inferred: DataFrame): Seq[DataType] =
    inferredDataTypes(exampleDataSchema)
      .to[List]
      .zip(inferred.schema)
      .map {
        case (f, sf) =>
          val values = inferred.select(sf.name).collect().map(_.get(0))
          f(values)
      }

  implicit val arbitraryDateFourDigits = Arbitrary[java.sql.Date](
    Gen
      .chooseNum[Long](0L, (new java.util.Date).getTime + 1000000)
      .map(new java.sql.Date(_))
  )

  implicit val arbitraryTimestamp = Arbitrary[java.sql.Timestamp](
    Gen
      .chooseNum[Long](0L, (new java.util.Date).getTime + 1000000)
      .map(new java.sql.Timestamp(_))
  )

  implicit val arbitraryBigDecimal =
    Arbitrary[BigDecimal](Gen.chooseNum[Double](-1.0e15, 1.0e15).map(BigDecimal.apply))

  implicit val arbitraryJavaBigDecimal =
    Arbitrary[java.math.BigDecimal](arbitraryBigDecimal.arbitrary.map(_.bigDecimal))

  // Unfortunately we're losing some precision when parsing Longs
  // due to the fact that we have to read them as Doubles and then cast.
  // We're restricting our tests to Int-sized Longs in order not to fail
  // because of this issue.
  implicit val arbitraryLongWithLosslessDoubleConvertability: Arbitrary[Long] =
    Arbitrary[Long] {
      arbitrary[Int].map(_.toLong)
    }

  implicit val arbitraryStringWithoutUnicodeCharacters: Arbitrary[String] =
    Arbitrary[String](Gen.alphaNumStr)

  val rowGen: Gen[ExampleData] = arbitrary[ExampleData]
  val rowsGen: Gen[List[ExampleData]] = Gen.listOf(rowGen)

}

class IntegrationSuite extends FunSpec with PropertyChecks with DataFrameSuiteBase with Matchers {

  import IntegrationSuite._

  import spark.implicits._

  implicit def shrinkOnlyNumberOfRows[A]: Shrink[List[A]] = Shrink.shrinkContainer[List, A]

  val PackageName = "com.crealytics.spark.excel"
  val sheetName = "test sheet"

  def runTests(maxRowsInMemory: Option[Int]) {

    def writeThenRead(df: DataFrame, schema: Option[StructType] = Some(exampleDataSchema)): DataFrame = {
      val fileName = File.createTempFile("spark_excel_test_", ".xlsx").getAbsolutePath

      df.write
        .format(PackageName)
        .option("sheetName", sheetName)
        .option("useHeader", "true")
        .mode("overwrite")
        .save(fileName)

      val reader = spark.read
        .format(PackageName)
        .option("sheetName", sheetName)
        .option("useHeader", "true")
        .option("treatEmptyValuesAsNulls", "true")
        .option("addColorColumns", "false")
      val withSchema = schema.foldLeft(reader.option("inferSchema", schema.isEmpty))(_ schema _)
      val withStreaming = maxRowsInMemory.foldLeft(withSchema) {
        case (reader, maxRowsInMem) => reader.option("maxRowsInMemory", maxRowsInMem)
      }
      withStreaming.load(fileName)
    }

    describe(s"with maxRowsInMemory = $maxRowsInMemory") {
      it("parses known datatypes correctly") {
        forAll(rowsGen, MinSuccessful(20)) { rows =>
          val expected = spark.createDataset(rows).toDF
          val actual = writeThenRead(expected)
          assertDataFrameApproximateEquals(expected, actual, relTol = 1.0E-6)
        }
      }

      it("handles null values correctly") {
        forAll(rowsGen, MinSuccessful(20)) { rows =>
          val expected = spark.createDataset(rows).toDF

          // We need two dataframes, one with null values, one with empty strings.
          // This is because we want ExcelFileSaver to write an empty string
          // if there's a null in that column.
          // expectedWithEmptyStr is what the dataframe should look
          // like when the Excel spreadsheet is saved.
          val expectedWithNull = expected.withColumn("aString", lit(null: String))
          // Generate the same DataFrame but with empty strings
          val expectedWithEmptyStr = expected.withColumn("aString", lit("": String))
          // Set the schema so that aString is nullable
          val fields = expectedWithEmptyStr.schema.fields
          fields.update(fields.indexWhere(_.name == "aString"), StructField("aString", DataTypes.StringType, true))

          assertDataFrameApproximateEquals(expectedWithEmptyStr, writeThenRead(expectedWithNull), relTol = 1.0E-6)
        }
      }

      it("infers schema correctly") {
        forAll(rowsGen, MinSuccessful(20)) { rows =>
          val df = spark.createDataset(rows).toDF
          val inferred = writeThenRead(df, schema = None)

          if (df.count() == 0) {
            // Without actual data, we assume everything is a StringType
            assert(inferred.schema.fields.forall(_.dataType == StringType))
          } else {
            val actualDataTypes = inferred.schema.fields.map(_.dataType).to[List]
            assert(actualDataTypes, expectedDataTypes(inferred))
          }
        }
      }

      it("returns all data rows when inferring schema") {
        forAll(rowsGen.filter(!_.isEmpty), MinSuccessful(20)) { rows =>
          val original = spark.createDataset(rows).toDF
          val inferred = writeThenRead(original, schema = None)
          val originalWithInferredColumnTypes =
            original.schema
              .zip(expectedDataTypes(inferred))
              .foldLeft(original) {
                case (df, (field, dataType)) =>
                  df.withColumn(field.name, df(field.name).cast(dataType))
              }
          val expected = spark.createDataFrame(originalWithInferredColumnTypes.rdd, inferred.schema)
          assertDataFrameEquals(expected, inferred)
        }
      }

      it("writes data with legal disclaimer") {
        val fileName = File.createTempFile("spark_excel_test_", ".xlsx").getAbsolutePath

        val df =
          Seq((1, "1", "2", "3"), (2, "4", "5", "6"), (3, "7", "8", "9")).toDF("id", "column1", "column2", "column3")

        df.write
          .format(PackageName)
          .option("sheetName", sheetName)
          .option("useHeader", "true")
          .option("legalDisclaimer", "All rights reserved")
          .mode("overwrite")
          .save(fileName)

        val firstRowReader = spark.read
          .format(PackageName)
          .option("sheetName", sheetName)
          .option("useHeader", "false")

        val dataReader = spark.read
          .format(PackageName)
          .option("sheetName", sheetName)
          .option("useHeader", "true")
          .option("skipFirstRows", "1")

        val legalDisclaimerResult = firstRowReader.load(fileName).limit(1).cache()
        legalDisclaimerResult.show()

        legalDisclaimerResult.collect() should contain theSameElementsAs Array(Row("All rights reserved"))

        val dataResult = dataReader.load(fileName).cache()
        dataResult.show()

        dataResult.schema.fields.map(_.name) should contain theSameElementsAs
          Array("id", "column1", "column2", "column3")
        dataResult.collect() should contain theSameElementsAs Array(
          Row("1", "1", "2", "3"),
          Row("2", "4", "5", "6"),
          Row("3", "7", "8", "9")
        )
      }

      it("writes specified file inside path and reads back") {
        val dir = Files.createTempDirectory("spark_excel_test")
        val dirName = dir.toString
        val fileName = "spark_excel_test.xlsx"

        val df =
          Seq((1, "1", "2", "3"), (2, "4", "5", "6"), (3, "7", "8", "9")).toDF("id", "column1", "column2", "column3")

        df.write
          .format(PackageName)
          .option("sheetName", sheetName)
          .option("useHeader", "true")
          .option("writeToFile", fileName)
          .mode("overwrite")
          .save(dirName)

        val result = spark.read
          .format(PackageName)
          .option("sheetName", sheetName)
          .option("useHeader", "true")
          .option("readFromFile", fileName)
          .load(dirName)
          .cache()

        result.show()

        result.schema.fields.map(_.name) should contain theSameElementsAs
          Array("id", "column1", "column2", "column3")
        result.collect() should contain theSameElementsAs Array(
          Row("1", "1", "2", "3"),
          Row("2", "4", "5", "6"),
          Row("3", "7", "8", "9")
        )

        new File(dir.toFile, "_SUCCESS").exists() shouldBe true
      }

    }
  }
  runTests(maxRowsInMemory = None)
  runTests(maxRowsInMemory = Some(20))
}
