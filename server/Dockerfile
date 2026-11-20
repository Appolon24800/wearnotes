FROM golang:1.27-alpine AS build

WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/wearnotes ./cmd/wearnotes

FROM alpine:3.22
RUN adduser -D -u 10001 wearnotes
USER wearnotes
WORKDIR /app
COPY --from=build /out/wearnotes /app/wearnotes
ENV WEARNOTES_ADDR=:8080
ENV WEARNOTES_DATA=/data
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["/app/wearnotes"]
