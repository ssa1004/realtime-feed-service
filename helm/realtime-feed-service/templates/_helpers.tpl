{{/*
공통 helper. Helm 표준 패턴 (name / fullname / labels / selectorLabels / serviceAccountName).
*/}}

{{- define "realtime-feed-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "realtime-feed-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "realtime-feed-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "realtime-feed-service.labels" -}}
helm.sh/chart: {{ include "realtime-feed-service.chart" . }}
{{ include "realtime-feed-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: realtime-feed-service
{{- end -}}

{{- define "realtime-feed-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "realtime-feed-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "realtime-feed-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "realtime-feed-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
이미지 태그가 비면 Chart.AppVersion 으로 fallback.
*/}}
{{- define "realtime-feed-service.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}
