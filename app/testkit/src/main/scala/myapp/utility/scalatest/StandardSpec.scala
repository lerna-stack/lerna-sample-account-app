package myapp.utility.scalatest

import lerna.util.lang.Equals
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

trait StandardSpec extends AnyWordSpecLike with SpecAssertions with Equals with Matchers
