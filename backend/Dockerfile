# Используем требуемую версию Java 11
FROM bellsoft/liberica-openjdk-alpine:11 AS builder

# Устанавливаем рабочую директорию
WORKDIR /build

# Сначала копируем только файлы Maven, чтобы закешировать зависимости.
# Если код изменится, а зависимости нет — Docker не будет качать их заново.
COPY .mvn/ .mvn
COPY mvnw mvnw.cmd pom.xml ./
RUN ./mvnw dependency:go-offline

# Теперь копируем исходный код и собираем проект
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Образ запуска приложения
FROM bellsoft/liberica-openjre-alpine:11 AS runner

# Создаем безопасного пользователя Spring
RUN addgroup -S spring && adduser -S spring -G spring

# Переключаемся на него
USER spring:spring

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем собранный JAR-файл в контейнер
COPY --from=builder /build/target/*.jar app.jar

# Открываем порт, на котором работает Spring Boot (по умолчанию 8080)
EXPOSE 8080

# Команда для запуска приложения с оптимизацией работы в контейнере
ENTRYPOINT ["java", "-jar", "app.jar"]