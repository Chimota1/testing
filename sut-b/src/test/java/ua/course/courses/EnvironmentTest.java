package ua.course.courses;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
class EnvironmentTest {

    // Контейнери для автоматичного запуску (Lab 5)
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine")
    );

    @Container
    static final GenericContainer<?> wiremockServer = new GenericContainer<>(
            DockerImageName.parse("wiremock/wiremock:3.9.2-alpine")
    ).withExposedPorts(8080);

    private Connection connection;
    private StudentRepository studentRepo;
    private CourseRepository courseRepo;
    private EnrollmentRepository enrollmentRepo;

    // Для Lab 4 (Mockito)
    private EnrollmentPolicy mockPolicy;
    private EnrollmentService service;

    // Для Lab 5 (Real HTTP + WireMock)
    private EnrollmentService httpService;

    @BeforeAll
    static void migrateDatabase() throws Exception {
        // Автоматичне накатування міграцій при старті контейнера БД
        String sql = Files.readString(Path.of("migrations/V1__create_tables.sql"));
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }

        // Прив'язка клієнта WireMock до порту контейнера
        WireMock.configureFor(wiremockServer.getHost(), wiremockServer.getMappedPort(8080));
    }

    @BeforeEach
    void setUp() throws SQLException {
        // Підключення береться автоматично з контейнера (пароль в IDEA більше не потрібен)
        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM enrollments");
            stmt.execute("DELETE FROM courses");
            stmt.execute("DELETE FROM students");
        }

        WireMock.reset();

        studentRepo = new StudentRepository(connection);
        courseRepo = new CourseRepository(connection);
        enrollmentRepo = new EnrollmentRepository(connection);

        // 1. Ініціалізація сервісу з Mock (для твоїх старих тестів)
        mockPolicy = Mockito.mock(EnrollmentPolicy.class);
        when(mockPolicy.isAllowed(any(Student.class), any(Course.class))).thenReturn(true);
        service = new EnrollmentService(studentRepo, courseRepo, enrollmentRepo, mockPolicy);

        // 2. Ініціалізація сервісу з реальним HTTP (для нових тестів Lab 5)
        String wiremockUrl = "http://" + wiremockServer.getHost() + ":" + wiremockServer.getMappedPort(8080);
        EnrollmentPolicy httpPolicy = new HttpEnrollmentPolicy(URI.create(wiremockUrl), Duration.ofSeconds(2));
        httpService = new EnrollmentService(studentRepo, courseRepo, enrollmentRepo, httpPolicy);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    @Tag("smoke")
    @DisplayName("Підключення до тестової бази даних")
    void connectsToDatabase() throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 1")) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    @DisplayName("Збереження або створення сутності (Repository State)")
    void shouldCreateAndRetrieveStudent_repositoryState() throws SQLException {
        Student newStudent = new Student(0L, "ST-001", 20, 95, true);
        Student savedStudent = studentRepo.create(newStudent);

        assertTrue(savedStudent.id() > 0, "БД має згенерувати id");

        Student retrievedStudent = studentRepo.findById(savedStudent.id());
        assertNotNull(retrievedStudent);
        assertEquals("ST-001", retrievedStudent.code());
    }

    @Test
    @DisplayName("Оновлення даних сутності (Repository State)")
    void shouldUpdateExistingStudent_repositoryState() throws SQLException {
        Student savedStudent = studentRepo.create(new Student(0L, "ST-002", 18, 80, false));

        Student updatedData = new Student(savedStudent.id(), "ST-002-UPD", 19, 85, true);
        studentRepo.update(updatedData);

        Student fromDb = studentRepo.findById(savedStudent.id());
        assertNotNull(fromDb);
        assertEquals("ST-002-UPD", fromDb.code());
        assertEquals(19, fromDb.age());
    }

    @Test
    @DisplayName("Видалення сутності")
    void shouldDeleteStudent() throws SQLException {
        Student savedStudent = studentRepo.create(new Student(0L, "ST-003", 25, 60, true));
        studentRepo.delete(savedStudent.id());

        Student fromDb = studentRepo.findById(savedStudent.id());
        assertNull(fromDb, "Слухач має бути видалений з БД");
    }

    @Test
    @DisplayName("Успішний сценарій зарахування (Service State)")
    void shouldSuccessfullyEnrollStudent_serviceState() throws SQLException {
        Student student = studentRepo.create(new Student(0L, "ST-100", 20, 90, true));
        Course course = courseRepo.create(new Course(0L, "C-JAVA", 500000L, 10, 1));

        EnrollmentRequest request = new EnrollmentRequest("REF-001", student.id(), course.id(), false);
        service.enroll(request);

        var enrollments = enrollmentRepo.findEnrollments(course.id(), EnrollmentStatus.Enrolled);
        assertEquals(1, enrollments.size(), "Має бути 1 активна заявка");
    }

    @Test
    @DisplayName("Відмова при відсутності вільних місць (Service State)")
    void shouldRejectEnrollmentWhenNoCapacity_serviceState() throws SQLException {
        Student student1 = studentRepo.create(new Student(0L, "ST-201", 20, 90, true));
        Student student2 = studentRepo.create(new Student(0L, "ST-202", 21, 95, true));
        Course course = courseRepo.create(new Course(0L, "C-SPRING", 500000L, 1, 1));

        service.enroll(new EnrollmentRequest("REF-201", student1.id(), course.id(), false));
        EnrollmentRequest request2 = new EnrollmentRequest("REF-202", student2.id(), course.id(), false);

        assertThrows(EnrollmentRejectedException.class, () -> service.enroll(request2));
    }

    @Test
    @DisplayName("Скасування заявки та перевірка стану (Persisted State)")
    void shouldCancelExistingEnrollment_persistedState() throws SQLException {
        Student student = studentRepo.create(new Student(0L, "ST-300", 25, 80, true));
        Course course = courseRepo.create(new Course(0L, "C-DATA", 100000L, 5, 2));

        service.enroll(new EnrollmentRequest("REF-300", student.id(), course.id(), false));
        long enrollmentId = enrollmentRepo.findEnrollments(course.id(), EnrollmentStatus.Enrolled).get(0).id();

        service.cancel(enrollmentId, 10);

        Enrollment cancelled = enrollmentRepo.findById(enrollmentId);
        assertEquals(EnrollmentStatus.Cancelled, cancelled.status(), "Статус у БД має бути Cancelled");
    }

    @Test
    @DisplayName("Персональне правило: відмова 16-річному для Рівня 3")
    void shouldRejectWhenAgeIsBelowLevelRequirement() throws SQLException {
        Student youngStudent = studentRepo.create(new Student(0L, "ST-400", 16, 99, true));
        Course level3Course = courseRepo.create(new Course(0L, "C-ARCH", 800000L, 20, 3));

        EnrollmentRequest request = new EnrollmentRequest("REF-400", youngStudent.id(), level3Course.id(), false);

        assertThrows(EnrollmentRejectedException.class, () -> service.enroll(request));
        assertFalse(enrollmentRepo.hasEnrollment(youngStudent.id(), level3Course.id()));
        Mockito.verifyNoInteractions(mockPolicy);
    }

    @Test
    @DisplayName("Персональне правило: допуск 19-річного для Рівня 3")
    void shouldAllowWhenAgeMeetsLevelRequirement() throws SQLException {
        Student olderStudent = studentRepo.create(new Student(0L, "ST-401", 19, 99, true));
        Course level3Course = courseRepo.create(new Course(0L, "C-ARCH-PRO", 800000L, 20, 3));

        EnrollmentRequest request = new EnrollmentRequest("REF-401", olderStudent.id(), level3Course.id(), false);

        service.enroll(request);
        assertTrue(enrollmentRepo.hasEnrollment(olderStudent.id(), level3Course.id()));
        Mockito.verify(mockPolicy).isAllowed(any(Student.class), any(Course.class));
    }

    // =========================================================================
    // НОВІ ТЕСТИ НИЖЧЕ (Лабораторна №5 — WireMock та HTTP)
    // =========================================================================

    @Test
    @DisplayName("Lab 5 (3.5): Успішне зарахування через WireMock HTTP")
    void shouldEnrollStudentWhenHttpPolicyAllows() throws SQLException {
        Student student = studentRepo.create(new Student(0L, "ST-501", 19, 90, true));
        Course course = courseRepo.create(new Course(0L, "C-HTTP-3", 250000L, 10, 3));

        stubFor(post(urlEqualTo("/enrollment-policy"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed\":true}")));

        EnrollmentRequest request = new EnrollmentRequest("REF-HTTP-OK", student.id(), course.id(), false);
        Enrollment result = httpService.enroll(request);

        Enrollment persisted = enrollmentRepo.findById(result.id());
        assertNotNull(persisted);
        assertEquals(EnrollmentStatus.Enrolled, persisted.status());
        assertEquals(250000L, persisted.total());

        verify(1, postRequestedFor(urlEqualTo("/enrollment-policy"))
                .withRequestBody(equalToJson("{\"studentId\":" + student.id() + ",\"courseId\":" + course.id() + "}")));
    }

    @Test
    @DisplayName("Lab 5 (3.6): Зовнішня відмова від WireMock (allowed=false)")
    void shouldRejectEnrollmentWhenHttpPolicyDenies() throws SQLException {
        Student student = studentRepo.create(new Student(0L, "ST-502", 18, 85, false));
        Course course = courseRepo.create(new Course(0L, "C-HTTP-2", 150000L, 5, 2));

        stubFor(post(urlEqualTo("/enrollment-policy"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed\":false}")));

        EnrollmentRequest request = new EnrollmentRequest("REF-HTTP-DENY", student.id(), course.id(), false);

        assertThrows(EnrollmentRejectedException.class, () -> httpService.enroll(request));
        assertFalse(enrollmentRepo.hasEnrollment(student.id(), course.id()));

        verify(1, postRequestedFor(urlEqualTo("/enrollment-policy"))
                .withRequestBody(equalToJson("{\"studentId\":" + student.id() + ",\"courseId\":" + course.id() + "}")));
    }

    @Test
    @DisplayName("Lab 5 (3.7): Недоступність зовнішнього сервісу (HTTP 503)")
    void shouldFailWithIllegalStateWhenWireMockReturns503() throws SQLException {
        Student student = studentRepo.create(new Student(0L, "ST-503", 20, 70, true));
        Course course = courseRepo.create(new Course(0L, "C-HTTP-1", 100000L, 15, 1));

        stubFor(post(urlEqualTo("/enrollment-policy"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("Service Unavailable")));

        EnrollmentRequest request = new EnrollmentRequest("REF-HTTP-503", student.id(), course.id(), false);

        assertThrows(IllegalStateException.class, () -> httpService.enroll(request));
        assertFalse(enrollmentRepo.hasEnrollment(student.id(), course.id()));

        verify(1, postRequestedFor(urlEqualTo("/enrollment-policy")));
    }

    @Test
    @DisplayName("Lab 5 (3.8): Локальна відмова за віком -> 0 запитів до WireMock")
    void shouldRejectLocallyWithoutCallingWireMock() throws SQLException {
        Student youngStudent = studentRepo.create(new Student(0L, "ST-504", 16, 100, true));
        Course level3Course = courseRepo.create(new Course(0L, "C-HTTP-HARD", 300000L, 10, 3));

        stubFor(post(urlEqualTo("/enrollment-policy"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed\":true}")));

        EnrollmentRequest request = new EnrollmentRequest("REF-HTTP-LOCAL", youngStudent.id(), level3Course.id(), false);

        assertThrows(EnrollmentRejectedException.class, () -> httpService.enroll(request));
        assertFalse(enrollmentRepo.hasEnrollment(youngStudent.id(), level3Course.id()));

        verify(0, postRequestedFor(anyUrl()));
    }
}