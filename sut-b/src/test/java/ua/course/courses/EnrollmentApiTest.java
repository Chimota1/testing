package ua.course.courses;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Лабораторна робота №6: Automated REST API Testing (SUT B, Варіант 10)")
class EnrollmentApiTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine")
    );

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // За замовчуванням порожня адреса -> (student, course) -> true
        registry.add("ENROLLMENT_POLICY_URL", () -> "");
    }

    @BeforeAll
    static void migrateDatabase() throws Exception {
        String sql = Files.readString(Path.of("migrations/V1__create_tables.sql"));
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM enrollments");
            stmt.execute("DELETE FROM courses");
            stmt.execute("DELETE FROM students");
        }
    }

    private long createStudent(String code, int age, int score, boolean regular) {
        return given()
                .contentType(ContentType.JSON)
                .body(new StudentController.Input(code, age, score, regular))
        .when()
                .post("/students")
        .then()
                .statusCode(201)
                .extract().jsonPath().getLong("id");
    }

    private long createCourse(String code, long price, int capacity, int level) {
        return given()
                .contentType(ContentType.JSON)
                .body(new CourseController.Input(code, price, capacity, level))
        .when()
                .post("/courses")
        .then()
                .statusCode(201)
                .extract().jsonPath().getLong("id");
    }

    // =========================================================================
    // 1. POST — створення ресурсу (статус 201, отримання id, перевірка полів)
    // =========================================================================
    @Test
    @Tag("smoke")
    @DisplayName("1. POST /enrollments — успішне створення ресурсу (статус 201, отримання id, перевірка response body)")
    void shouldCreateEnrollmentAndReturn201WithCorrectFields() {
        long studentId = createStudent("ST-POST-1", 20, 85, true);
        long courseId = createCourse("C-POST-1", 450000L, 10, 1);

        EnrollmentRequest request = new EnrollmentRequest("REF-POST-01", studentId, courseId, false);

        given()
                .contentType(ContentType.JSON)
                .body(request)
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201)
                .header("Location", containsString("/enrollments/"))
                .body("id", greaterThan(0))
                .body("reference", equalTo("REF-POST-01"))
                .body("studentId", equalTo((int) studentId))
                .body("courseId", equalTo((int) courseId))
                .body("total", equalTo(450000))
                .body("prepaid", equalTo(450000))
                .body("refund", equalTo(0))
                .body("status", equalTo("Enrolled"));
    }

    // =========================================================================
    // 2. GET за id (статус 200, дані відповідають створеному ресурсу)
    // =========================================================================
    @Test
    @DisplayName("2. GET /enrollments/{id} — отримання ресурсу за id (статус 200, перевірка відповідності даних)")
    void shouldGetEnrollmentByIdAndVerifyData() {
        long studentId = createStudent("ST-GET-1", 22, 90, false);
        long courseId = createCourse("C-GET-1", 600000L, 5, 2);

        EnrollmentRequest request = new EnrollmentRequest("REF-GET-01", studentId, courseId, false);

        long createdId = given()
                .contentType(ContentType.JSON)
                .body(request)
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201)
                .extract().jsonPath().getLong("id");

        given()
                .accept(ContentType.JSON)
        .when()
                .get("/enrollments/{id}", createdId)
        .then()
                .statusCode(200)
                .body("id", equalTo((int) createdId))
                .body("reference", equalTo("REF-GET-01"))
                .body("studentId", equalTo((int) studentId))
                .body("courseId", equalTo((int) courseId))
                .body("total", equalTo(600000))
                .body("prepaid", equalTo(600000))
                .body("refund", equalTo(0))
                .body("status", equalTo("Enrolled"));
    }

    // =========================================================================
    // 3. GET неіснуючого ресурсу (статус 404, перевірка code і message)
    // =========================================================================
    @Test
    @Tag("smoke")
    @DisplayName("3. GET /enrollments/{id} — неіснуючий ресурс (статус 404, error contract з code і message)")
    void shouldReturn404WithErrorContractWhenResourceDoesNotExist() {
        long nonExistentId = 999999L;

        given()
                .accept(ContentType.JSON)
        .when()
                .get("/enrollments/{id}", nonExistentId)
        .then()
                .statusCode(404)
                .body("code", equalTo("NOT_FOUND"))
                .body("message", equalTo("Заявку не знайдено"));
    }

    @Test
    @DisplayName("3.1 GET неіснуючого студента або курсу — 404 NOT_FOUND")
    void shouldReturn404WhenStudentOrCourseDoesNotExist() {
        given()
                .accept(ContentType.JSON)
        .when()
                .get("/students/{id}", 888888L)
        .then()
                .statusCode(404)
                .body("code", equalTo("NOT_FOUND"))
                .body("message", equalTo("Слухача не знайдено"));

        given()
                .accept(ContentType.JSON)
        .when()
                .get("/courses/{id}", 777777L)
        .then()
                .statusCode(404)
                .body("code", equalTo("NOT_FOUND"))
                .body("message", equalTo("Курс не знайдено"));
    }

    // =========================================================================
    // 4. Action endpoint (complete / cancel) — статус 200, новий стан
    // =========================================================================
    @Test
    @Tag("smoke")
    @DisplayName("4.1 Action: POST /enrollments/{id}/complete — завершення (статус 200, перехід у Completed)")
    void shouldCompleteEnrollmentViaActionEndpoint() {
        long studentId = createStudent("ST-ACT-1", 21, 75, true);
        long courseId = createCourse("C-ACT-1", 300000L, 8, 1);

        long enrollmentId = given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-ACT-01", studentId, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201)
                .extract().jsonPath().getLong("id");

        given()
                .accept(ContentType.JSON)
        .when()
                .post("/enrollments/{id}/complete", enrollmentId)
        .then()
                .statusCode(200)
                .body("id", equalTo((int) enrollmentId))
                .body("status", equalTo("Completed"))
                .body("total", equalTo(300000))
                .body("prepaid", equalTo(300000))
                .body("refund", equalTo(0));
    }

    @Test
    @DisplayName("4.2 Action: POST /enrollments/{id}/cancel — скасування (статус 200, перехід у Cancelled, виплата refund)")
    void shouldCancelEnrollmentViaActionEndpointWithRefund() {
        long studentId = createStudent("ST-ACT-2", 23, 80, false);
        long courseId = createCourse("C-ACT-2", 500000L, 10, 2);

        long enrollmentId = given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-ACT-02", studentId, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201)
                .extract().jsonPath().getLong("id");

        given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentsController.CancelInput(14))
        .when()
                .post("/enrollments/{id}/cancel", enrollmentId)
        .then()
                .statusCode(200)
                .body("id", equalTo((int) enrollmentId))
                .body("status", equalTo("Cancelled"))
                .body("total", equalTo(500000))
                .body("prepaid", equalTo(500000))
                .body("refund", equalTo(500000));
    }

    // =========================================================================
    // 5. Повторна або заборонена дія (статус 409, перевірка error response)
    // =========================================================================
    @Test
    @DisplayName("5.1 Заборонена дія: повторна спроба зміни статусу вже завершеної заявки (статус 409 ENROLLMENT_CONFLICT)")
    void shouldReturn409WhenAttemptingForbiddenActionOnCompletedEnrollment() {
        long studentId = createStudent("ST-FORB-1", 20, 80, true);
        long courseId = createCourse("C-FORB-1", 400000L, 5, 1);

        long enrollmentId = given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-FORB-01", studentId, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201)
                .extract().jsonPath().getLong("id");

        // Завершуємо заявку перший раз — успішно (200)
        given()
        .when()
                .post("/enrollments/{id}/complete", enrollmentId)
        .then()
                .statusCode(200);

        // Повторна дія: спроба скасувати вже завершену заявку — заборонено (409)
        given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentsController.CancelInput(5))
        .when()
                .post("/enrollments/{id}/cancel", enrollmentId)
        .then()
                .statusCode(409)
                .body("code", equalTo("ENROLLMENT_CONFLICT"))
                .body("message", equalTo("Зміна статусу заборонена"));

        // Повторна дія: повторне завершення — також заборонено (409)
        given()
        .when()
                .post("/enrollments/{id}/complete", enrollmentId)
        .then()
                .statusCode(409)
                .body("code", equalTo("ENROLLMENT_CONFLICT"))
                .body("message", equalTo("Зміна статусу заборонена"));
    }

    @Test
    @DisplayName("5.2 Заборонена дія: повторне зарахування того самого слухача на той самий курс (статус 409)")
    void shouldReturn409WhenEnrollingSameStudentTwiceInCourse() {
        long studentId = createStudent("ST-FORB-2", 22, 95, false);
        long courseId = createCourse("C-FORB-2", 350000L, 10, 1);

        // Перше зарахування — успіх 201
        given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-FORB-2A", studentId, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201);

        // Повторне зарахування того самого слухача — конфлікт 409
        given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-FORB-2B", studentId, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(409)
                .body("code", equalTo("ENROLLMENT_CONFLICT"))
                .body("message", equalTo("Слухача вже зараховано або він завершив курс"));
    }

    // =========================================================================
    // 6. Незмінність після відхилення (після 409 наступний GET підтверджує незмінність)
    // =========================================================================
    @Test
    @DisplayName("6. Незмінність ресурсу: після 409 GET підтверджує, що ресурс залишився в попередньому стані")
    void shouldVerifyResourceStateRemainsUnchangedAfter409Conflict() {
        long studentId = createStudent("ST-IMMUT-1", 20, 85, true);
        long courseId = createCourse("C-IMMUT-1", 500000L, 5, 1);

        long enrollmentId = given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-IMMUT-01", studentId, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201)
                .extract().jsonPath().getLong("id");

        // Успішно переводимо в стан Completed
        given()
        .when()
                .post("/enrollments/{id}/complete", enrollmentId)
        .then()
                .statusCode(200);

        // Відхилена дія -> 409
        given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentsController.CancelInput(10))
        .when()
                .post("/enrollments/{id}/cancel", enrollmentId)
        .then()
                .statusCode(409);

        // Наступний GET перевіряє стан ресурсу — стан залишився Completed, суми не змінені!
        given()
                .accept(ContentType.JSON)
        .when()
                .get("/enrollments/{id}", enrollmentId)
        .then()
                .statusCode(200)
                .body("id", equalTo((int) enrollmentId))
                .body("status", equalTo("Completed"))
                .body("refund", equalTo(0))
                .body("total", equalTo(500000))
                .body("prepaid", equalTo(500000));
    }

    // =========================================================================
    // 7. Персональне бізнес-правило (параметризація, Варіант 10: вікова межа за рівнем)
    // Правило: Level 1 -> вік >= 14, Level 2 -> вік >= 16, Level 3 -> вік >= 18.
    // =========================================================================
    @ParameterizedTest(name = "[{index}] Вік {0} років, курс рівень {1} -> Очікуваний статус {2}")
    @CsvSource({
            "16, 2, 201, true",   // Рівень 2 вимагає >= 16 років -> успіх (201)
            "15, 2, 409, false",  // Рівень 2 при віці 15 -> відхилено (409)
            "18, 3, 201, true",   // Рівень 3 вимагає >= 18 років -> успіх (201)
            "16, 3, 409, false",  // Рівень 3 при віці 16 -> відхилено (409)
            "14, 1, 201, true"    // Рівень 1 вимагає >= 14 років -> успіх (201)
    })
    @DisplayName("7. Персональне бізнес-правило: параметризована перевірка вікового цензу за рівнем курсу")
    void shouldValidateAgeRequirementPerCourseLevel_parameterized(
            int age, int level, int expectedStatus, boolean shouldSucceed) {

        String studentCode = "ST-RULE-" + age + "-" + level;
        String courseCode = "C-RULE-" + level + "-" + age;
        String ref = "REF-RULE-" + age + "-" + level;

        long studentId = createStudent(studentCode, age, 80, true);
        long courseId = createCourse(courseCode, 400000L, 10, level);

        var requestSpec = given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest(ref, studentId, courseId, false));

        if (shouldSucceed) {
            requestSpec
            .when()
                    .post("/enrollments")
            .then()
                    .statusCode(expectedStatus)
                    .body("status", equalTo("Enrolled"))
                    .body("studentId", equalTo((int) studentId))
                    .body("courseId", equalTo((int) courseId));
        } else {
            requestSpec
            .when()
                    .post("/enrollments")
            .then()
                    .statusCode(expectedStatus)
                    .body("code", equalTo("ENROLLMENT_CONFLICT"))
                    .body("message", equalTo("Персональні умови допуску не виконані"));
        }
    }

    // =========================================================================
    // 8. Workflow: POST -> GET -> action -> GET з динамічною передачею id
    // =========================================================================
    @Test
    @Tag("smoke")
    @DisplayName("8. Workflow: POST -> GET -> action(complete) -> GET з динамічною передачею id між запитами")
    void shouldExecuteCompleteWorkflowWithDynamicIdPassing() {
        long studentId = createStudent("ST-FLOW-1", 24, 90, true);
        long courseId = createCourse("C-FLOW-1", 750000L, 15, 2);

        // Крок 1: POST — створення ресурсу та динамічне отримання id
        long dynamicEnrollmentId = given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-FLOW-01", studentId, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201)
                .body("status", equalTo("Enrolled"))
                .extract().jsonPath().getLong("id");

        // Крок 2: GET за динамічним id — перевірка початкового стану
        given()
                .accept(ContentType.JSON)
        .when()
                .get("/enrollments/{id}", dynamicEnrollmentId)
        .then()
                .statusCode(200)
                .body("id", equalTo((int) dynamicEnrollmentId))
                .body("status", equalTo("Enrolled"))
                .body("total", equalTo(750000))
                .body("refund", equalTo(0));

        // Крок 3: Action endpoint за динамічним id — перехід стану
        given()
                .accept(ContentType.JSON)
        .when()
                .post("/enrollments/{id}/complete", dynamicEnrollmentId)
        .then()
                .statusCode(200)
                .body("id", equalTo((int) dynamicEnrollmentId))
                .body("status", equalTo("Completed"));

        // Крок 4: Фінальний GET за динамічним id — підтвердження збереженого стану
        given()
                .accept(ContentType.JSON)
        .when()
                .get("/enrollments/{id}", dynamicEnrollmentId)
        .then()
                .statusCode(200)
                .body("id", equalTo((int) dynamicEnrollmentId))
                .body("status", equalTo("Completed"))
                .body("total", equalTo(750000))
                .body("prepaid", equalTo(750000))
                .body("refund", equalTo(0));
    }

    @Test
    @DisplayName("8.1 Додатковий Workflow: POST -> GET -> action(cancel) -> GET")
    void shouldExecuteCancelWorkflowWithDynamicIdPassing() {
        long studentId = createStudent("ST-FLOW-2", 19, 85, false);
        long courseId = createCourse("C-FLOW-2", 500000L, 10, 1);

        // 1. POST
        long dynamicId = given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-FLOW-02", studentId, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201)
                .extract().jsonPath().getLong("id");

        // 2. GET
        given()
        .when()
                .get("/enrollments/{id}", dynamicId)
        .then()
                .statusCode(200)
                .body("status", equalTo("Enrolled"));

        // 3. Action (cancel)
        given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentsController.CancelInput(10))
        .when()
                .post("/enrollments/{id}/cancel", dynamicId)
        .then()
                .statusCode(200)
                .body("status", equalTo("Cancelled"))
                .body("refund", equalTo(500000));

        // 4. Final GET
        given()
        .when()
                .get("/enrollments/{id}", dynamicId)
        .then()
                .statusCode(200)
                .body("status", equalTo("Cancelled"))
                .body("refund", equalTo(500000))
                .body("prepaid", equalTo(500000));
    }

    // =========================================================================
    // 9. Перевірка вичерпання місткості курсу (Capacity limit -> 409)
    // =========================================================================
    @Test
    @DisplayName("9. Бізнес-правило: відмова при вичерпанні місткості курсу (статус 409 ENROLLMENT_CONFLICT)")
    void shouldRejectWhenCourseCapacityIsExceeded() {
        long courseId = createCourse("C-CAP-1", 300000L, 1, 1); // capacity = 1
        long student1 = createStudent("ST-CAP-1", 20, 80, true);
        long student2 = createStudent("ST-CAP-2", 21, 85, true);

        // Перше зарахування займає єдине місце
        given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-CAP-1", student1, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(201);

        // Друге зарахування відхиляється через відсутність вільних місць
        given()
                .contentType(ContentType.JSON)
                .body(new EnrollmentRequest("REF-CAP-2", student2, courseId, false))
        .when()
                .post("/enrollments")
        .then()
                .statusCode(409)
                .body("code", equalTo("ENROLLMENT_CONFLICT"))
                .body("message", equalTo("Вільних місць немає"));
    }
}
