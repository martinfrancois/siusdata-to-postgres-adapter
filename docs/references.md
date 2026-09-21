# References

## SIUS start list field description

- Title as printed on the first page: "Beschreibung des Startlisten Formats für SIUSData"
- Publisher: SIUS AG
- Version and date: the document prints neither. The file name carries `V1`, and the PDF metadata gives 3 April 2023 as the creation date.
- Retrieved on 21 September 2026 from https://support.sius.com/wp-content/uploads/2023/04/Startlistenfelder-Beschreibung-SIUSData-_-SIUSData-DE-V1.pdf (German)
- Describes the fields of the start list files (`_stl.csv`) that SIUSData exports next to the result files. This adapter skips those files.

## SIUS support forum

- "Exported SIUSDATA .CSV-file", https://support.sius.com/forums/topic/exported-siusdata-csv-file/, read 21 September 2026. A SIUS staff member states that the `Relay` field is present only for compatibility and is no longer used, because relays are managed in SIUSRank.
- "Items of exported csv-file", https://support.sius.com/forums/topic/items-of-exported-csv-file/, read 21 September 2026. A SIUS staff member says the full field tables for the shot data and the start list are in the SIUSData help, reached through the `?` menu, not in a separate downloadable file.

- "SIUSData Socket Information", https://support.sius.com/forums/topic/siusdata-socket-information/, read 21 September 2026. A SIUS staff member states that, given the age of SIUSData and because the new SR24 full STYX ranges no longer work with it, SIUS no longer supports requests about its interfaces, and that SiusAPI gives full access to the SIUS LON system and is still supported; it is available on request by email to SIUS support, comes with examples, and programming support is not included.

## Independent C# parser

- github.com/mteinum/siusdata, commit `d4c5af813b03ad5265d501e7bd5e7192027cde9e`, read 21 September 2026. An independent C# parser of the SIUSData export. It states no license and has no README, so all rights are reserved by its author. It was consulted only to confirm field meanings (the per-field comments in `SiusData/ShotData.cs` and `SiusData/Shooter.cs`) and to compare export shapes; no code, comment text or test data was copied. Where its reading of a column matches this adapter's parser and the fixtures, `siusdata-format.md` states the meaning in its own words; where it is the only source, the page says "according to an independent parser".
