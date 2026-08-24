# ADR-003: Docker MySQL Development Environment

## Status

Accepted

## Context

개발 환경에서 Database 버전과 설정 차이를 줄이고, 동일한 개발 환경을 재현하기 위해 Docker 기반 MySQL 사용을 검토한다.

## Considered Options

### Option A: Local MySQL Installation

장점:

- 설정이 단순함
- 별도 Container 관리 필요 없음

단점:

- 개발 환경 차이 발생 가능
- 버전 관리 어려움

### Option B: Docker MySQL

장점:

- 환경 재현 가능
- 버전 고정 가능
- 프로젝트 실행 환경 공유 가능

단점:

- Docker 관리 필요
- Volume 관리 필요

## Decision

Development Database는 Docker MySQL을 사용한다.

MySQL Version은 8.4 LTS로 고정하고, Local execution은 Docker Compose를 사용한다.

## Rationale

프로젝트 환경 재현성과 관리 편의성을 우선한다.

단, Database 데이터는 Docker Volume에 저장하여 Container Lifecycle과 데이터 Lifecycle을 분리한다.

## Consequences

장점:

- 동일한 Database 환경 재현 가능
- 프로젝트 onboarding 개선
- Container 재생성 시에도 Volume이 유지되면 Database 데이터 유지 가능

주의:

- Volume 삭제 시 데이터가 삭제될 수 있으므로 주의한다.
- Docker Compose configuration file은 별도 Task에서 생성한다.
