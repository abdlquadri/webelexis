/*
 * This file is part of Webelexis. Copyright (c) 2015 by G. Weirich
 */

import io.vertx.core.Vertx;
import io.vertx.ext.unit.TestOptions;
import io.vertx.ext.unit.TestSuite;
import io.vertx.ext.unit.report.ReportOptions;

/**
 * Created by gerry on 26.06.15.
 */
public class Tests {

    public static void main(String[] args) {
        TestSuite suite = TestSuite.create("Webelexis Server");
        suite.test("test", context -> {
            Vertx vertx=Vertx.vertx();
            vertx.deployVerticle("ch.webelexis.CoreVerticle");
            String s = "hallo";
            context.assertEquals(s, "hallo");
        });
        suite.run(new TestOptions().addReporter(new ReportOptions().setTo("console")));
        System.exit(0);
    }

}
