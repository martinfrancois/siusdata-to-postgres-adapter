# The SIUSData export as this adapter reads it

This page is written from the parser in `SiusDataToPostgresAdapter.java`. It describes what the code does with the files, not what SIUS specifies; SIUS's own documents are listed in [references.md](references.md).

## The three file kinds

SIUSData writes three kinds of file next to each other. The adapter watches one folder and reads a file when its name starts with eight digits and ends with `.csv` in any letter case, for example `20250928.csv` or `20250928_all.csv`. Files whose name ends in `_stl.csv` or `_mod.csv` are skipped by name.

- Main file, for example `20250928.csv`: the shot data. This is the only kind the adapter stores.
- Start list, `_stl.csv`: the list of shooters. SIUS documents its fields in the start list field description cited in references.md. The adapter reads none of these fields. It carries names, so it is left out on purpose.
- Modification file, `_mod.csv`: shots changed by hand in SIUSData. The adapter does not support it. In the two fixture pairs in `src/test/resources/reproduction`, each `_mod` row also appears once, unchanged, in the matching main file: both rows of `20250928_mod.csv` and both rows of `20250930_mod.csv` are already in `20250928.csv` and `20250930.csv`. So on these examples the modification file repeats shots that the main file already holds; appending it would double them. SIUS publishes no specification for the modification file, so what triggers its rows is not established here.

A main file has no header line. Every line is one shot with 28 fields separated by `;`. The adapter reads fields by position, so a line with fewer fields fails and a line with more fields is rejected by the CSV reader once the field count differs from the first line. The adapter remembers in the `file_progress` table how many lines of each file it has stored and continues from there when the file grows.

## Fields

Positions count from 1. The type is the column type in `siusdata_shots`. An integer or bigint field that is not a number, and an empty text or boolean field, is stored as `NULL` and logged as an error; text is trimmed; a boolean is true only for `1`.

| Field | Column | Type |
| ---: | --- | --- |
| 1 | `start_number` | integer |
| 2 | `score` | text |
| 3 | `phase` | integer |
| 4 | `target_number` | integer |
| 5 | `score2` | text |
| 6 | `score3` | text |
| 7 | `time` | text, the clock time as exported, for example `17:54:34.70` |
| 8 | `is_inner_ten` | boolean |
| 9 | `coordinate_x` | text |
| 10 | `coordinate_y` | text |
| 11 | `is_in_time` | boolean |
| 12 | `light_phase_time_span` | text |
| 13 | `is_right_sweep` | boolean |
| 14 | `is_demo` | boolean |
| 15 | `shoot_ordinal` | integer |
| 16 | `practice_ordinal` | integer |
| 17 | `manual_status` | integer |
| 18 | `total_kind` | integer |
| 19 | `group_ordinal` | integer |
| 20 | `fire_kind` | integer |
| 21 | `log_event_id` | bigint |
| 22 | `log_type` | integer |
| 23 | `date` | timestamp, computed as described below |
| 24 | `relay` | integer |
| 25 | `weapon` | integer |
| 26 | `position` | integer |
| 27 | `target_code` | integer |
| 28 | `external_number` | integer |

The file name is stored with every row in the `filename` column.

Text fields are stored exactly as they arrive, only trimmed. The adapter applies no numeric parsing to `score`, `score2`, `score3`, the coordinates or `light_phase_time_span`, so their encoding reaches the database unchanged. There is no cap on the score columns; the fixtures include values such as 87, 98 and 103.

## What some columns mean

The meanings below come from the fixtures and, where noted, from an independent C# parser (see references.md). SIUS's own field tables are in the SIUSData help, not in a public file.

The start numbers in the test fixtures under `src/test/resources` are meaningless on their own. The mapping from a start number to a person exists only in the start list of the SIUS installation that produced the file, and this repository holds no such mapping; its start list fixtures carry placeholder names.

- `log_type` is 3 on ordinary shots and 12 on the hand-modified rows in `_mod` files. `manual_status` is 0 on ordinary shots and 2 on those same rows.
- `log_event_id` counts up through a match but is not unique. In `20250930.csv` the value 16 occurs on seven different lanes, and 68 of the 85 values repeat. The adapter stores it as `bigint` and never uses it as a key: the primary key of `siusdata_shots` is a generated `id`, and `file_progress` is keyed by file name. So the repeats do not matter.
- `light_phase_time_span` is the time since the light phase changed, in hundredths of a second, according to the independent parser. The fixture values confirm hundredths, not a decimal: in `20250930.csv` the first two shots are 29.89 s apart by their clock time, their `light_phase_time_span` values are `319.20` and `349.9`, and 349.9 read as 349 seconds and 9 hundredths gives that same 29.89 s gap, while reading it as the decimal 349.9 gives 30.70. The adapter stores the field as text unchanged, so this makes no difference to what is stored, but a reader who treats the column as a number should read it as `seconds.hundredths`, not a decimal.
- `relay` is 0 in the fixtures. SIUS states on its support forum that the relay field is kept only for compatibility and is no longer used; relays are managed in SIUSRank (see references.md).

## The date column

Field 23 holds the time since the start of the year in hundredths of a second (0.01 s units), which the independent parser and the fixtures agree on. The adapter reads it as a 64-bit number and multiplies by 10 to get milliseconds, so the values past 2^31 in the fixtures (up to 3039748777) do not overflow. It then takes the first four characters of the file name as the year, starts at 1 January of that year at midnight in the system time zone, and adds the milliseconds; the result is stored in the `date` timestamp column. A value that is not a number, or one so large that the result does not fit a timestamp, is stored as `NULL` and logged. With real SIUSData exports the derived date matches the shooting day. The test fixtures produce odd dates, for example April and August for a file named `20250928.csv`, only because they contain test data, not real recordings.
