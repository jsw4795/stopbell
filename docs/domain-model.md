# StopBell Domain Model

## Purpose

Domain Model은 StopBell 서비스에서 다루는 핵심 개념과 관계를 정의한다.

Database Schema와 달리 단순히 컬럼을 정의하는 것이 아니라, 서비스에서
어떤 개념이 존재하고 각 개념이 어떤 책임을 가지는지 표현한다.

------------------------------------------------------------------------

# Core Domain

## Domain Relationship

    User

     |
     |
     └── Alarm


    Transit API

     |
     ├── BusRoute
     |
     └── BusStop

------------------------------------------------------------------------

# User

## Purpose

서비스 내부에서 사용자를 식별하고 Alarm 소유자의 기준이 되는 최소 Domain이다.

## Responsibilities

-   내부 사용자 식별
-   Alarm 소유자 기준
-   향후 Device 및 Authentication identity 연결 기준

## Main Attributes

    id

    createdAt

    updatedAt

## Identifier Policy

Java에서는 `Long`을 사용한다.

Database에서는 MySQL `BIGINT AUTO_INCREMENT`를 사용한다. JPA 구현 시에는 MySQL `AUTO_INCREMENT`와 호환되는 ID 생성 방식을 사용한다.

현재 프로젝트 규모에서는 UUID 등 별도 식별 전략을 도입하지 않는다.

## Timestamp Policy

`createdAt`, `updatedAt`은 Java에서 `LocalDateTime`, Database에서 `DATETIME(6)`으로 관리한다. 두 컬럼은 `NOT NULL`을 기본 정책으로 한다.

timestamp 자동 관리 방식은 아직 확정하지 않는다. TASK-101 구현 시 JPA lifecycle callback 또는 Spring Data Auditing 중 프로젝트의 단순성과 향후 공통 Entity 확장 가능성을 고려해 최소한의 방식을 선택한다.

이번 단계에서는 timestamp 관리를 위해 `BaseEntity` 같은 공통 상속 구조를 도입하지 않는다.

## Relationship

    User 1 : N Alarm

한 사용자는 여러 개의 Alarm을 등록할 수 있다.

User Entity에는 `alarms` collection을 추가하지 않는다. Alarm 구현 시 Alarm이 User를 참조하는 방향을 우선하며, JPA 양방향 관계는 실제 필요가 생길 때 검토한다.

## Persistence

    JPA

User는 단순한 Domain CRUD와 Entity 상태 관리를 위해 JPA Repository 기반으로 관리한다.

------------------------------------------------------------------------

# Alarm

## Purpose

사용자가 원하는 알림 조건의 공통 정보를 표현하는 핵심 Domain이다.

예:

"143번 버스가 서울역 정류장에 도착하면 알려줘"

라는 사용자의 요청 하나가 하나의 Alarm이다.

## Responsibilities

-   Alarm 공통 정보 관리
-   활성 상태 관리

## Main Attributes

    id

    user

    transitType

    active

    createdAt

    updatedAt

## Active Status

`active`는 Alarm이 현재 감시 대상인지만 표현한다. 새 Alarm은 비활성 상태(`false`)로 생성한다.

Transit API 조회 실패, Notification 발송 결과, Alarm trigger는 Alarm의 상태가 아니다. 이 정보는 필요 시 `NotificationHistory`, Application Log 또는 별도 이력으로 분리한다.

`transitType`은 `BUS`, `SUBWAY`를 표현하는 Enum으로 관리하며, Database에는 문자열로 저장한다.

Transit provider와 실제 식별자 체계가 확정되기 전에는 route, stop, line, station, direction 같은 Transit-specific 속성을 Alarm에 추가하지 않는다.

## Persistence

    JPA

Alarm은 생성, 수정, 삭제와 상태 관리를 위해 JPA Repository 기반으로 관리한다.

------------------------------------------------------------------------

# BusRoute

## Purpose

버스 노선 정보를 표현한다.

예:

    143번
    273번

## Main Attributes

    id

    routeNumber

    region

    providerId

## Relationship

    BusRoute 1 : N BusStop

## Persistence

    MyBatis

BusRoute는 Transit 관련 조회 Model로 사용하며, 복잡한 검색과 외부 Transit 데이터 처리가 필요한 Query는 MyBatis로 처리한다.

------------------------------------------------------------------------

# BusStop

## Purpose

버스 정류장 정보를 표현한다.

예:

    서울역
    강남역
    잠실역

## Main Attributes

    id

    name

    latitude

    longitude

    providerId

## Relationship

실제로 하나의 정류장은 여러 노선에 포함될 수 있다.

따라서 Database에서는 중간 테이블이 필요할 가능성이 있다.

예:

    bus_routes

    bus_stops

    route_stops

## Persistence

    MyBatis

BusStop은 Transit 관련 조회 Model로 사용하며, Bus Route와의 관계를 포함한 Query는 MyBatis로 처리한다.

------------------------------------------------------------------------

# TransitEvent

## Purpose

외부 Transit API에서 가져온 현재 교통 상태를 표현한다.

Database Entity라기보다 Domain 개념이다.

예:

    143번 버스

    서울역 정류장

    도착 예정

    30초 후

## Main Attributes

    routeId

    stopId

    arrivalTime

    vehicleId

    updatedAt

## Reason

Alarm과 외부 API 데이터를 분리하기 위해 사용한다.

## Persistence

    MyBatis

TransitEvent는 외부 Transit 데이터를 조회하고 정규화하는 Query Model로 사용한다. 영속화 여부와 관계없이 Transit 상태 조회는 MyBatis로 처리한다.

Bad:

    Alarm

     ↓

    Bus API 호출

     ↓

    판단

Good:

    Transit API

     ↓

    TransitEvent

     ↓

    Alarm Evaluation

     ↓

    Notification

------------------------------------------------------------------------

# NotificationHistory

## Purpose

알림 발송 기록.

## Responsibilities

-   어떤 Alarm인지
-   언제 발송했는지
-   발송 결과가 무엇인지

기록한다.

## Main Attributes

    id

    alarm

    status

    failureReason

    createdAt

`status`는 `SUCCESS`, `FAILURE`만 가지는 Enum으로 표현하고 Database에는 문자열로 저장한다.

`failureReason`은 실패 시 한 줄 수준의 간단한 원인을 기록할 수 있으며 `null`을 허용한다. Provider별 응답 구조나 FCM message ID는 현재 저장하지 않는다.

`createdAt`은 발송 결과 History가 생성된 시각이다. History는 현재 생성 후 일반적으로 수정하지 않는 방향이므로 `updatedAt`은 두지 않는다.

## Relationship

    Alarm 1 : N NotificationHistory

NotificationHistory가 `Alarm`을 참조하는 단방향 관계를 사용한다. Alarm Entity에는 NotificationHistory collection을 추가하지 않는다.

## Persistence

    JPA

NotificationHistory는 알림 발송 기록의 저장과 상태 관리를 위해 JPA Repository 기반으로 관리한다.

------------------------------------------------------------------------

# Domain Relationship Overview

                     User

                      |

                      |

                     Alarm


    Transit API

          |

    BusRoute / BusStop



    Transit API

          |

    TransitEvent

          |

    Alarm Evaluation

------------------------------------------------------------------------

# Undecided Items

현재 결정하지 않는 사항.

## Authentication

Authentication provider는 아직 Undecided이다.

email 사용 여부와 정책도 Undecided이다. provider identity를 User에 포함할지, 별도 `AuthIdentity`로 분리할지도 Authentication 설계 시점에 확정한다.

현재는 `email`, `provider`, `providerUserId`, `displayName`을 User의 속성으로 추가하지 않는다. Authentication provider와 `AuthIdentity` 구조도 미리 구현하거나 확정하지 않는다.

후보:

-   OAuth2
-   JWT
-   Session

## Alarm Trigger Rule

예:

    arrivalTime <= 0

    또는

    도착 예정 1분 전

정확한 기준은 Transit API 확인 후 결정한다.

## TransitEvent Persistence

아직 결정하지 않는다.

가능한 방향:

-   실시간 데이터이므로 저장하지 않음
-   추후 분석 기능을 위해 저장
