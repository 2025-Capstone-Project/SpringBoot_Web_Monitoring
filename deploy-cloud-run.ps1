param(
  [string]$ProjectId,
  [string]$Region = "asia-northeast3",
  [string]$Service = "web-monitoring",
  [string]$Image = "web-monitoring:cloudrun"
)

$ErrorActionPreference = "Stop"

if (-not $ProjectId) {
  Write-Error "Provide -ProjectId <GCP_PROJECT_ID>"
}

# Build image
Write-Host "Building image..."
docker build -t $Image .

# Tag to Artifact Registry (adjust registry path if needed)
$Repo = "$Region-docker.pkg.dev/$ProjectId/web/$Service"
docker tag $Image "$Repo:latest"

Write-Host "Pushing image..."
docker push "$Repo:latest"

Write-Host "Deploying to Cloud Run..."
gcloud run deploy $Service `
  --image "$Repo:latest" `
  --project $ProjectId `
  --region $Region `
  --platform managed `
  --allow-unauthenticated `
  --max-instances 3 `
  --port 8080 `
  --set-env-vars "JAVA_OPTS=-Xms256m -Xmx512m"

Write-Host "Done. Remember to set sensitive envs like INFLUX_TOKEN via Cloud Run > Service > Variables or Secrets."

