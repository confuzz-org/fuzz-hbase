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
    String tasks = conf.get("hbase.regionserver.msginterval");
    System.out.println("tasks: " + tasks);
    String options = conf.get("mapred.map.child.java.opts");
    System.out.println("options: " + options);
    //if (Integer.valueOf(tasks) < 50) {
	//  throw new RuntimeException("tasks < 50");
    //}
    String s = conf.get("fs.s3a.select.output.csv.quote.fields");
    System.out.println("s: " + s);
    if (Objects.equals(s, "asneeded")) {
      throw new RuntimeException("fake asneeded bug");
    }  else {
      System.out.println("haha");
    }
  }
}
