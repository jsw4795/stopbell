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
            |
            |
            ├── TransitTarget
            |
            └── NotificationHistory


    TransitTarget

     |
     ├── BusRoute
     |
     └── BusStop

------------------------------------------------------------------------

# User

## Purpose

서비스를 사용하는 사용자.

## Responsibilities

-   사용자 식별
-   인증 정보 관리
-   등록한 Alarm 관리

## Main Attributes

    id

    email

    provider

    createdAt

    updatedAt

## Relationship

    User 1 : N Alarm

한 사용자는 여러 개의 Alarm을 등록할 수 있다.

## Persistence

    JPA

User는 단순한 Domain CRUD와 Entity 상태 관리를 위해 JPA Repository 기반으로 관리한다.

------------------------------------------------------------------------

# Alarm

## Purpose

사용자가 원하는 알림 조건을 표현하는 핵심 Domain이다.

예:

"143번 버스가 서울역 정류장에 도착하면 알려줘"

라는 사용자의 요청 하나가 하나의 Alarm이다.

## Responsibilities

-   알림 조건 관리
-   활성 상태 관리
-   감시 대상 연결

## Main Attributes

    id

    userId

    transitType

    targetId

    status

    createdAt

    updatedAt

## Status

    ACTIVE

    PAUSED

    COMPLETED

    DELETED

## State Flow

    사용자가 알림 생성

            ↓

    ACTIVE

            ↓

    버스 도착

            ↓

    COMPLETED

            ↓

    사용자 삭제

            ↓

    DELETED

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

    alarmId

    sentAt

    status

    failureReason

## Persistence

    JPA

NotificationHistory는 알림 발송 기록의 저장과 상태 관리를 위해 JPA Repository 기반으로 관리한다.

------------------------------------------------------------------------

# Domain Relationship Overview

                     User

                      |

                      |

                     Alarm

                      |

              ┌───────┴────────┐

              |                |

        TransitTarget     NotificationHistory


              |

         ┌────┴────┐

         |         |

    BusRoute   BusStop



    Transit API

          |

    TransitEvent

          |

    Alarm Evaluation

------------------------------------------------------------------------

# Undecided Items

현재 결정하지 않는 사항.

## Authentication

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
