# ADR-004: Database Migration Tool Selection

## Status

Accepted

## Context

StopBell은 Spring Boot 기반 Backend와 MySQL Database를 사용한다.

프로젝트 규모가 증가하면 Database Schema 변경 이력을 관리해야 하며, 개발 환경과 운영 환경에서 동일한 Schema 상태를 재현할 필요가 있다.

고려 사항:

- JPA Entity와 Database Schema 변경 관리
- MyBatis SQL 기반 Query 관리
- Migration History 관리
- 새로운 개발 환경 재현성

## Considered Options

### Option A: Hibernate ddl-auto

장점:

- 초기 개발이 편리함

단점:

- Schema 변경 이력 관리 어려움
- 운영 환경에서 예측하기 어려운 변경 가능성

### Option B: Liquibase

장점:

- 다양한 Schema 관리 방식 지원

단점:

- XML/YAML 기반 관리가 복잡할 수 있음

### Option C: Flyway

장점:

- SQL 기반 Migration
- Spring Boot 생태계와 높은 호환성
- MyBatis와 자연스러운 조합
- Migration History 관리 용이

## Decision

StopBell Database Migration Tool은 Flyway를 사용한다.

## Rationale

StopBell은 JPA와 MyBatis를 함께 사용하는 구조이다.

SQL 기반 Migration 방식이 프로젝트 방향과 가장 잘 맞으며, Database Schema 변경을 명확하게 관리하기 위해 Flyway를 선택한다.

## Consequences

장점:

- Schema 변경 이력 관리 가능
- 새로운 개발 환경 재현 가능
- 운영 Database 변경 추적 가능

주의:

- Schema 변경 시 Migration 파일을 반드시 추가한다.
- Entity 변경만으로 Database Schema를 변경하지 않는다.
