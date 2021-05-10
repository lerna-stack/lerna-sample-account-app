package myapp.utility.scalatest

import org.scalatest.{ MustMatchers, WordSpecLike }
import lerna.util.lang.Equals

trait StandardSpec extends WordSpecLike with SpecAssertions with Equals with MustMatchers
