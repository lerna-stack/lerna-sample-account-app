package myapp.readmodel.schema

import scala.util.Random

object TableSeeds {

  def apply(tables: Tables): TableSeeds = new TableSeeds(tables)

  trait ValueGenerator[+T] extends (() => T)

  private def arbitraryValue[T](implicit gen: ValueGenerator[T]): T = gen()

  @scala.annotation.nowarn("msg=is never used")
  private implicit def optionValueGenerator[T](implicit gen: ValueGenerator[T]): ValueGenerator[Option[T]] =
    () => Option(gen())

  private implicit object StringValueGenerator extends ValueGenerator[String] {
    override def apply(): String = Random.alphanumeric.take(2).mkString
  }

  private implicit object BooleanValueGenerator extends ValueGenerator[Boolean] {
    override def apply(): Boolean = Random.nextBoolean()
  }

  private implicit object LongValueGenerator extends ValueGenerator[Long] {
    override def apply(): Long = Random.nextLong(10)
  }

  @scala.annotation.nowarn("msg=is never used")
  private implicit object BigDecimalValueGenerator extends ValueGenerator[scala.math.BigDecimal] {
    override def apply(): scala.math.BigDecimal = BigDecimal(Random.nextInt(10))
  }

  private implicit object TimestampValueGenerator extends ValueGenerator[java.sql.Timestamp] {
    override def apply(): java.sql.Timestamp = new java.sql.Timestamp(Random.nextInt(Int.MaxValue))
  }
}

class TableSeeds private (val tables: Tables) {
  import TableSeeds._

  lazy val AkkaProjectionOffsetStoreRowSeed: tables.AkkaProjectionOffsetStoreRow =
    tables.AkkaProjectionOffsetStoreRow(
      arbitraryValue,
      arbitraryValue,
      arbitraryValue,
      arbitraryValue,
      arbitraryValue,
      arbitraryValue,
    )

  lazy val DepositStoreRowSeed: tables.DepositStoreRow =
    tables.DepositStoreRow(
      arbitraryValue,
      arbitraryValue,
      arbitraryValue,
      arbitraryValue,
    )
}
