# Docker/Cloud Run Guide

## Build
```powershell
# Windows PowerShell
Docker Desktop를 먼저 실행하세요.
docker build -t web-monitoring:dev .
```

## Run (local)
- 방법 A: 환경변수 직접 주입
```powershell
docker run --rm -p 8080:8080 -e PORT=8080 -e INFLUX_TOKEN=your-token web-monitoring:dev
```
- 방법 B: .env 마운트 (entrypoint가 자동 로드)
```powershell
# 프로젝트 루트에서 .env를 준비한 뒤 실행
# 예시 .env는 .env.example 참조

docker run --rm -p 8080:8080 -v ${PWD}\.env:/app/.env web-monitoring:dev
```

## Cloud Run
- Artifact Registry에 푸시 후 배포 스크립트 사용
```powershell
# 사전 준비: gcloud CLI 로그인 및 프로젝트/리전 초기화
# .\deploy-cloud-run.ps1 -ProjectId <GCP_PROJECT_ID> -Region asia-northeast3 -Service web-monitoring
```
- 민감정보(INFLUX_TOKEN)는 Cloud Run 서비스 환경변수 또는 Secret으로 설정

## Notes
- entrypoint.sh가 /app/.env를 탐지하면 자동으로 export(set -a)합니다.
- Cloud Run에선 $PORT를 서비스가 지정하므로 server.port는 자동으로 맞춰집니다.
- Windows 개행(CRLF) 문제는 Dockerfile에서 sed로 정규화

