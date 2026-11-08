package ua.course.courses.performance;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.HttpProtocolBuilder;

public class CoursesPerformanceSimulation extends Simulation {
    private static final String baseUrl = System.getProperty("perf.baseUrl", "http://127.0.0.1:8080");
    private static final String testType = System.getProperty("gatling.testType", "baseline");
    private static final Profile profile = Profile.from(testType);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(baseUrl)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ChainBuilder scenario = exec(
            http("Create student")
                    .post("/students")
                    .body(StringBody("""
                            {"code":"student-#{randomInt(10000000,99999999)}","age":25,"entryScore":80,"regular":true}
                            """))
                    .check(status().is(201), jsonPath("$.id").saveAs("studentId")))
            .exec(
                    http("Get created student")
                            .get("/students/#{studentId}")
                            .check(status().is(200), jsonPath("$.id").isEL("#{studentId}")))
            .exec(
                    http("Create course")
                            .post("/courses")
                            .body(StringBody("""
                                    {"code":"course-#{randomInt(10000000,99999999)}","price":10000,"capacity":100,"level":1}
                                    """))
                            .check(status().is(201), jsonPath("$.id").saveAs("courseId")))
            .exec(
                    http("Get created course")
                            .get("/courses/#{courseId}")
                            .check(status().is(200), jsonPath("$.id").isEL("#{courseId}")))
            .exec(
                    http("Create enrollment")
                            .post("/enrollments")
                            .body(StringBody("""
                                    {"reference":"ref-#{randomInt(10000000,99999999)}","studentId":#{studentId},"courseId":#{courseId},"couponValid":false}
                                    """))
                            .check(status().is(201), jsonPath("$.id").saveAs("enrollmentId")))
            .exec(
                    http("Get created enrollment")
                            .get("/enrollments/#{enrollmentId}")
                            .check(status().is(200), jsonPath("$.id").isEL("#{enrollmentId}")));

    {
        setUp(scenario("Courses API journey").exec(scenario).injectOpen(profile.injection()))
                .protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile3().lt(profile.maxP95Millis()),
                        global().failedRequests().percent().lt(profile.maxErrorRatePercent()));
    }

    private record Profile(
            String name,
            OpenInjectionStep injection,
            int maxP95Millis,
            double maxErrorRatePercent) {

        static Profile from(String value) {
            return switch (value.toLowerCase()) {
                case "baseline" -> new Profile(
                        "baseline", atOnceUsers(1), 1000, 1.0);
                case "load" -> new Profile(
                        "load", rampUsersPerSec(1).to(10).during(30), 1500, 1.0);
                case "stress", "spike" -> new Profile(
                        "stress", stressPeakUsers(100), 3000, 5.0);
                default -> throw new IllegalArgumentException(
                        "Unknown gatling.testType: " + value + ". Use baseline, load, or stress.");
            };
        }

        private static OpenInjectionStep stressPeakUsers(int users) {
            return atOnceUsers(users);
        }
    }
}
