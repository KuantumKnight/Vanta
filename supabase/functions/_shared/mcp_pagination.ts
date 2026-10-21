export function pageBounds(offset: number, limit: number): [number, number] {
  if (!Number.isInteger(offset) || offset < 0) {
    throw new RangeError("offset must be a non-negative integer");
  }
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
    throw new RangeError("limit must be between 1 and 100");
  }
  return [offset, offset + limit - 1];
}
