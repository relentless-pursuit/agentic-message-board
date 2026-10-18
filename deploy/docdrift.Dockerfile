# DocDrift as a service: webhook receiver + usage cards. Build from the repository root:
#   docker build -f deploy/docdrift.Dockerfile -t docdrift .
#   docker run -p 127.0.0.1:8099:8099 -e DOCDRIFT_WEBHOOK_SECRET=... -v /path/to/repo:/repo docdrift
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 1001 docdrift && git config --system --add safe.directory '*'
WORKDIR /app
COPY src/docdrift ./src/docdrift
USER docdrift
EXPOSE 8099
# OPENAI_API_KEY is optional; without it the rule-based reasoner is used. Mount the repository to watch at /repo.
ENTRYPOINT ["python", "src/docdrift", "serve", "--repo", "/repo", "--host", "0.0.0.0", "--port", "8099"]
