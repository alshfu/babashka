# Сигналинг «Пульта». Одна зависимость (ws), никакой сборки.
FROM node:22-alpine

WORKDIR /app

COPY server/package.json server/package-lock.json* ./server/
RUN cd server && npm ci --omit=dev || npm install --omit=dev

COPY server ./server
COPY web ./web
COPY protocol ./protocol

# Журнал метаданных лежит в томе: он переживает пересборку образа.
RUN mkdir -p /data && chown -R node:node /data /app
USER node

ENV PORT=8080 JOURNAL_PATH=/data/journal.jsonl
EXPOSE 8080

CMD ["node", "server/src/index.js"]
