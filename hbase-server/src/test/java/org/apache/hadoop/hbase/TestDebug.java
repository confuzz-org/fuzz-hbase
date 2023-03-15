package org.apache.hadoop.hbase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.testclassification.MiscTests;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import java.util.Objects;

@Category({ SmallTests.class })
public class TestDebug {
  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestDebug.class);

  @Test
  public void test() {
    Configuration conf = HBaseConfiguration.create();
    String school = conf.get("school", "UIUC");
    String name = conf.get("name", "confuzz");
    if (Objects.equals(name, "bug")) {
      throw new IllegalArgumentException("fake bug");
    } else {
      System.out.println("Hello " + school + " " + name);
    }
  }
}
