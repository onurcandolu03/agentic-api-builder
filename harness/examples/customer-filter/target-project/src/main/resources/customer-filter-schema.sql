-- Documentation for the logical in-memory adapter only. Never executed.
CREATE TABLE CUSTOMER_FILTER (
  FILTER_ID VARCHAR(100) PRIMARY KEY,
  DISPLAY_LABEL VARCHAR(200) NOT NULL,
  PARENT_KEY VARCHAR(100) NOT NULL,
  FILTER_ORDER INTEGER NOT NULL,
  UNIQUE (PARENT_KEY, FILTER_ORDER)
);
-- Request 'after' resolves an existing sibling FILTER_ID; it is not a new physical column.
