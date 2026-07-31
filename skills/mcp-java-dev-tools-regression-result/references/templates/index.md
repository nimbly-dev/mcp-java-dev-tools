# Result Templates Index

Default template id: `endpoint_table_result`

## Registered Templates

1. `endpoint_table_result`
   - mode: `table`
   - group: `http_result_table`
   - file: `http_result_table/endpoint_table_result.md`
   - required columns:
     - `Endpoint`
     - `Status`
     - `HTTP Code`
     - `Duration (ms)`
     - `Probe Coverage`
   - optional columns:
     - `Memory (bytes)` (contract-defined only)
   - optional section:
     - `failed_assertions` (rendered only for failed or blocked trigger-step assertions)
