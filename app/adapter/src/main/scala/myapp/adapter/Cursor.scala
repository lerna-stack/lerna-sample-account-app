package myapp.adapter

final case class Cursor(cursor: String) extends AnyVal {
  def lowerThan(other: Cursor): Boolean = {
    cursor.toInt < other.cursor.toInt
  }
}
