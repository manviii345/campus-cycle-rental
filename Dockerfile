# Campus Cycle Rental - single-container deploy (Java backend serves API + built React frontend)
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . .
RUN cd backend && javac -cp "lib/*" -d bin src/main/java/com/cyclerental/*.java \
    && mkdir -p /app/data /app/uploads
# Render injects PORT. DB_URL/SEED_DEMO are set in the Render dashboard.
ENV FRONTEND_DIR=/app/frontend/dist \
    UPLOAD_DIR=/app/uploads \
    DB_URL=jdbc:sqlite:/app/data/cyclerental.db
CMD ["java", "-cp", "backend/bin:backend/lib/*", "com.cyclerental.Main"]
