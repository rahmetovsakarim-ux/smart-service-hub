# REST API

JSON over HTTP. Authentication is a server-side session (`JSESSIONID` cookie) created by `POST /api/auth/login`.

**Conventions**

- Error body: `{"message": "<Kazakh>||<Russian>"}`. The UI shows the half that matches the selected language.
- State-changing requests (`POST`, `PUT`, `DELETE`) must send the `X-XSRF-TOKEN` header with the value of the
  `XSRF-TOKEN` cookie. A request without it is answered with `403`.
- Status codes: `400` validation / business rule, `401` not signed in (or session expired), `403` role or ownership,
  `404` not found, `409` conflict (duplicate, already paid), `429` too many failed logins.
- Dates are ISO-8601 local date-times (`2030-05-20T10:00:00`).

## Auth

| Method | URL | Access | Body → response |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | public | `{fullName, phone, email?, password, role: CLIENT\|WORKER, specializationId?, district?, experienceYears?, bio?}` → `UserDto` (`201`). `ADMIN` cannot be registered |
| POST | `/api/auth/login` | public | `{login, password}` (phone or e-mail) → `UserDto`. Rotates the session id; throttled (`429`) |
| POST | `/api/auth/logout` | any | – |
| GET | `/api/auth/me` | signed in | → `UserDto` (`401` when not signed in) |

## Catalog (public)

| Method | URL | Response |
| --- | --- | --- |
| GET | `/api/catalog/categories` | `[{id, nameKk, nameRu}]` |
| GET | `/api/catalog/services?categoryId=&q=` | `[ServiceDto]` – active services only |
| GET | `/api/catalog/districts` | `["CENTER","NORTH","SOUTH","WEST","EAST"]` |

`ServiceDto`: `{id, categoryId, categoryNameKk, categoryNameRu, nameKk, nameRu, descriptionKk, descriptionRu, price,
durationMinutes (nullable), active, ratingAvg, ratingCount, availableSpecialists}`. The last three describe the category's specialists: the
review-weighted average rating, the number of reviews behind it, and how many active specialists currently accept
orders. They are `0` in admin editing responses.

## Client (`ROLE_CLIENT`)

| Method | URL | Description |
| --- | --- | --- |
| POST | `/api/client/orders` | Place an order `{serviceId, address, district, scheduledTime, note?}` → `OrderDto` (`201`). A specialist is assigned automatically when one is free |
| GET | `/api/client/orders?status=` | My orders |
| POST | `/api/client/orders/{id}/pay` | `{cardNumber}` → `{status: PAID\|FAILED, transactionRef, message}`. Only completed orders, only once (`409` if already paid or in progress) |
| POST | `/api/client/orders/{id}/review` | `{rating: 1..5, comment?}` (`201`). Only completed orders, once |

## Specialist (`ROLE_WORKER`)

| Method | URL | Description |
| --- | --- | --- |
| GET | `/api/worker/orders?status=` | Orders assigned to me |
| POST | `/api/worker/orders/{id}/accept` | `NEW → ACCEPTED` |
| POST | `/api/worker/orders/{id}/reject` | Decline a `NEW` order; it is re-assigned to another specialist, or administrators are alerted |
| POST | `/api/worker/orders/{id}/status` | `{status: IN_PROGRESS\|COMPLETED}`, one step forward only |
| GET / PUT | `/api/worker/profile` | `{experienceYears?, bio?, district?, available?}` |
| GET | `/api/worker/reviews` | Reviews I received |

## Shared (signed in)

| Method | URL | Description |
| --- | --- | --- |
| GET | `/api/orders/{id}` | Order with status history. Only the client, the assigned specialist or an administrator |
| GET | `/api/notifications` | `{unread, items:[{id, orderId, messageKk, messageRu, seen, createdAt}]}` (latest 50) |
| POST | `/api/notifications/read-all` | Mark all as read |

## Administrator (`ROLE_ADMIN`)

| Method | URL | Description |
| --- | --- | --- |
| GET | `/api/admin/orders?status=&clientId=&workerId=` | Up to 200 latest orders |
| GET | `/api/admin/orders/{id}/candidates` | Specialists who could take the order |
| POST | `/api/admin/orders/{id}/assign` | `{workerId}`, only for `NEW` orders |
| POST / PUT / DELETE | `/api/admin/categories[/{id}]` | Category CRUD (`409` when it still has services or specialists) |
| GET / POST / PUT / DELETE | `/api/admin/services[/{id}]` | Service CRUD (delete = hide, so order history stays intact) |
| GET | `/api/admin/users?role=` | Users |
| POST | `/api/admin/users/{id}/active` | `{active}` – block / unblock (administrators cannot be blocked) |
| GET | `/api/admin/stats?days=14` | Totals, by status, by role, per specialist, per day, revenue |

## Order lifecycle

```
NEW ──accept──▶ ACCEPTED ──start──▶ IN_PROGRESS ──finish──▶ COMPLETED ──pay / review
 │
 └─reject (specialist declines): re-assigned to another specialist, or left unassigned and administrators notified
```

Every transition and assignment is written to the order's audit history.
