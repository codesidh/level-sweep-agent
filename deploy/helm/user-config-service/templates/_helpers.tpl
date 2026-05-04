{{/*
Common helpers for the user-config-service chart. Mirrors the journal-service
chart's _helpers.tpl one-for-one — same naming + label scheme so operators
have a single mental model across services.
*/}}

{{/*
Chart name (truncated to 63 chars for K8s label compliance).
*/}}
{{- define "user-config-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name. If release name already contains the chart name,
don't double it up.
*/}}
{{- define "user-config-service.fullname" -}}
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

{{/*
Chart label (chart-name + version, dot-replaced for label safety).
*/}}
{{- define "user-config-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels applied to every resource.
*/}}
{{- define "user-config-service.labels" -}}
helm.sh/chart: {{ include "user-config-service.chart" . }}
{{ include "user-config-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: levelsweep
{{- end -}}

{{/*
Selector labels — used in matchLabels (must NOT contain version).
*/}}
{{- define "user-config-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "user-config-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Resolved ServiceAccount name.
*/}}
{{- define "user-config-service.serviceAccountName" -}}
{{- default (include "user-config-service.fullname" .) .Values.serviceAccount.name -}}
{{- end -}}

{{/*
Synced K8s secret name for a given KV object. Truncated to 63 chars.
*/}}
{{- define "user-config-service.kvSecretName" -}}
{{- printf "%s-%s" (include "user-config-service.fullname" .root) .object | trunc 63 | trimSuffix "-" -}}
{{- end -}}
