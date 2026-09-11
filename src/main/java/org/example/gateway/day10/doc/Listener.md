# Listener

**개요**

`Listener`는 게이트웨이가 외부 요청을 수신할 포트와 프로토콜, TLS 설정을 정의하는 리소스입니다.

**필수 필드**

필드명 | 필수 | 설명
---|---|---
apiVersion | Yes | 리소스 버전
kind | Yes | 리소스 종류
uid | Yes | 리소스 UID
workspaceId | Yes | 워크스페이스 ID
id | Yes | 리소스 ID
name | Yes | 리소스 이름
version | Yes | 리소스 버전 문자열
description | Yes | 리소스 설명
metadata.name | Yes | 메타데이터 이름
spec.protocol | Yes | 통신 프로토콜 (HTTP, HTTPS, TCP, GRPC)
spec.port | Yes | 바인딩할 포트 번호

**스키마**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["apiVersion", "kind", "uid", "workspaceId", "id", "name", "version", "description", "metadata", "spec"],
  "properties": {
    "apiVersion": { "type": "string", "const": "iip.gateway/v1alpha1" },
    "kind": { "type": "string", "const": "Listener" },
    "uid": { "type": "string" },
    "workspaceId": { "type": "string" },
    "id": { "type": "string" },
    "name": { "type": "string" },
    "version": { "type": "string" },
    "description": { "type": "string" },
    "metadata": {
      "type": "object",
      "required": ["name"],
      "properties": {
        "name": { "type": "string" }
      }
    },
    "spec": {
      "type": "object",
      "required": ["protocol", "port"],
      "properties": {
        "protocol": {
          "type": "string",
          "enum": ["HTTP", "HTTPS", "TCP", "GRPC"]
        },
        "port": {
          "type": "integer",
          "minimum": 1,
          "maximum": 65535
        },
        "host": {
          "type": "string",
          "default": "0.0.0.0",
          "pattern": "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$"
        },
        "allowedHostnames": {
          "type": "array",
          "items": { "type": "string" }
        },
        "tls": {
          "type": "object",
          "properties": {
            "mode": { "type": "string", "enum": ["TERMINATE", "PASSTHROUGH"] },
            "minVersion": { "type": "string", "enum": ["1.2", "1.3"] },
            "certificates": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["certRef", "keyRef"],
                "properties": {
                  "certRef": { "type": "string" },
                  "keyRef": { "type": "string" }
                }
              }
            }
          }
        },
        "connection": {
          "type": "object",
          "properties": {
            "readTimeout": { "type": "integer", "format": "int64", "minimum": 0, "description": "milliseconds" },
            "writeTimeout": { "type": "integer", "format": "int64", "minimum": 0, "description": "milliseconds" },
            "maxConnections": { "type": "integer" }
          }
        }
      }
    }
  }
}
```

**예시**

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Listener",
  "uid": "UUID",
  "workspaceId": "dev",
  "id": "ecommerce-https-listener",
  "name": "ecommerce-https-listener",
  "version": "v1",
  "description": "ecommerce https listener",
  "metadata": {
    "name": "ecommerce-https-listener"
  },
  "spec": {
    "protocol": "HTTPS",
    "port": 8443,
    "host": "0.0.0.0",
    "allowedHostnames": [
      "api.ecommerce.com",
      "payment.ecommerce.com"
    ],
    "tls": {
      "mode": "TERMINATE",
      "minVersion": "1.3",
      "certificates": [
        {
          "certRef": "/etc/certs/tls.crt",
          "keyRef": "/etc/certs/tls.key"
        }
      ]
    },
    "connection": {
      "readTimeout": 10000,
      "writeTimeout": 10000,
      "maxConnections": 10000
    }
  }
}
```
