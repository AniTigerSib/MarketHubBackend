# Listing collection and detail responses

All paths below are served through the gateway. Collection responses are compact
SQL projections: full photo arrays and full descriptions are not loaded and then
removed in Java. No database migration is required.

## Response fields

| Endpoint | Item fields |
|---|---|
| `GET /listings` | `id`, `title`, `price`, `priceUnit`, `categoryId`, `cityId`, `createdAt`, `coverPhotoUrl`, `condition`, `sellerType`, `favorite`, `descriptionPreview` |
| `GET /favorites` | `id`, `title`, `price`, `priceUnit`, `categoryId`, `cityId`, `createdAt`, `coverPhotoUrl`, `favorite` |
| `GET /listings/mine` | `id`, `title`, `price`, `priceUnit`, `categoryId`, `coverPhotoUrl`, `status`, `favoritesCount` |

`coverPhotoUrl` is the first photo URL, or `null` if there are no photos.
`descriptionPreview` contains the first 200 PostgreSQL characters, with no added
ellipsis; short descriptions are returned intact. Search matches the full title and
description even though the response only includes the preview.

`favorite` reflects the authenticated viewer on search results and is false for
anonymous requests. Every item returned by `/favorites` has `favorite: true`.
The account rows retain `categoryId` so the UI can format service prices correctly.

All other full-detail fields (including `sellerId`, `subcategoryId`, `description`,
`photoUrls`, contact flags and `updatedAt`) are available from `GET /listings/{id}`.
Create, update, and archive operations continue returning the full `ListingView`.
Active details are public; archived details remain visible only to their owner.
Favorites continue to include saved listings that have since been archived.

## Pagination

All three collections accept `page` (zero-based, default `0`, minimum `0`) and
`size` (default `24`, range `1`–`100`). Invalid values return `400`.
Search and favorites keep the same envelope:

```json
{"items": [], "total": 0, "page": 0, "size": 24}
```

The caller's listings add pagination metadata to the existing envelope:

```json
{
  "items": [],
  "total": 0,
  "page": 0,
  "size": 24,
  "counts": {"active": 0, "archived": 0}
}
```

`total` counts every matching item before pagination. For `/listings/mine`, it
respects `status=active|archived`; omit status to include both. `counts` always
contains the caller's totals across all statuses, regardless of page or filter.
Pages beyond the end return an empty `items` array with the matching total.
Mine is sorted newest first, with ID as a tie-breaker. Search keeps its existing
sort options; favorites remain ordered by most recently saved, then listing ID.

Example: `GET /listings/mine?status=active&page=1&size=20` returns the second
page of active rows and status counts for both account tabs.

## Frontend migration

These collection item changes require clients using the old `ListingView` payload
to update their types and mappings when integrating these endpoints.

- Home and favorite cards: use `coverPhotoUrl` instead of `photoUrls[0]`; use
  `cityId` and `createdAt` to render the location/date metadata.
- Search rows: render `descriptionPreview` instead of `description`, alongside
  `condition` and `sellerType` badges. Use the response `total` for pagination.
- Account rows: use `status` and `favoritesCount`; get tab badges from `counts`
  instead of counting only the items on the current page.
- Listing detail routes: fetch `GET /listings/{id}` by the route ID, including on
  direct navigation, rather than treating a cached collection item as full detail.
- Edit actions: fetch `GET /listings/{id}` with the owner's token before filling
  the edit form, so it gets the full description, all photos, and contact settings.

The OpenAPI Listings definition exposes each collection's separate response schema.
