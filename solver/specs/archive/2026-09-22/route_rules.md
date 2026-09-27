# Technical Appendix for District Heating Connection Routing

> Amended 2026-09-22 with the organisers' answers (see [CHANGELOG.md](CHANGELOG.md)). Amendments are marked inline and take precedence over the original text where they differ.

Terminology: **OKS** denotes a capital construction facility, such as a building; **prospective OKS** means a planned facility to be connected. **Nominal diameter** (`DN` in the formulas below) is the standardized pipe size in millimeters. A **tie-in** connects the new network to the existing network. A **heat chamber** is an underground structure containing pipeline equipment and connections. **Upstream** means toward the heat source. Flows are in metric tonnes per hour (`t/h`).

## 1. Purpose

This technical appendix defines the input and output data, mandatory calculation parameters, and rules for generating, calculating, and comparing options for connecting prospective OKS to an existing district heating network.

## 2. Input data

The service receives one GeoJSON `FeatureCollection` file. Input geometry uses WGS 84 (`EPSG:4326`). All length, distance, and buffer calculations must be performed after transforming geometry to `EPSG:32637` (WGS 84 / UTM zone 37N).

The file contains several object types. Each type has its own mandatory attribute set.

### 2.1. Input object types

| Object | `object_type` | Geometry |
| --- | --- | --- |
| Heat source | `source` | `Point` |
| Existing heating network | `heat_network` | `LineString` |
| Existing heat chamber | `heat_chamber` | `Point` |
| Prospective OKS | `oks_future` | `Polygon` / `MultiPolygon` |
| Connection point of a prospective OKS | `oks_connection_point` | `Point` |
| Existing OKS | `oks_existing` | `Polygon` / `MultiPolygon` |
| Spatial restriction | `restriction` | As appropriate for the restriction type |

A spatial restriction also has a `restriction_type`, such as `road`, `gas_pipeline`, or `power_cable`. Section 5 defines restriction handling rules.

### 2.2. Input object attributes

| Attribute | Technical name | Data type | Applies to | Description |
| --- | --- | --- | --- | --- |
| Object identifier | `id` | `string` | All objects | Unique identifier within the input file. |
| Object type | `object_type` | `string` | All objects | One of the values in Section 2.1. |
| Nominal diameter | `diameter` | `integer` | `heat_network`, `heat_chamber` | For a network section: current nominal diameter. For a chamber: maximum nominal diameter of existing sections already adjoining it. Unit: mm. |
| Design flow | `flow_tph` | `number` | `heat_network`, `oks_future` | For the existing network: current design flow. For a prospective OKS: flow used to calculate the new network. Unit: t/h. |
| Heat load | `heat_load` | `number` | `oks_future` | Reference heat load of the prospective OKS, Gcal/h. |
| Prospective OKS identifier | `oks_id` | `string` | `oks_connection_point` | ID of the `oks_future` object to which the connection point belongs. |
| Restriction type | `restriction_type` | `string` | `restriction` | Spatial restriction type; handling rules are in Section 5.1. |
| ID of the next object toward the source | `upstream_object_id` | `string` | `heat_network`, `heat_chamber` | ID of the next existing object toward the source. May reference `heat_network`, `heat_chamber`, or `source`. |

Each existing heating network section has constant nominal diameter and design flow values. The sequence of existing sections and chambers is defined by `upstream_object_id`. Following the chain toward the source leads to the source object.

## 3. Basic calculation rules for the new network

The baseline installation method is underground direct burial, without a duct. One linear object of the new heating network represents a supply/return pipeline pair with the same nominal diameter.

Supply and return flows must not be added together. One flow value is used for the pipe pair.

If a new section serves several OKS, its design flow equals the sum of those OKS flows.

For each section, select the smallest nominal diameter whose capacity is at least the section's design flow.

Check the maximum length for each continuous portion of the network with one nominal diameter. A heat chamber without a diameter change does not restart the length count. A nominal diameter change starts a new count.

**Amendment (2026-09-22, organisers' Q&A):** When the limit is exceeded, raise the nominal diameter by exactly one table size (200 to 250), never more. At a branch without a diameter change, branch lengths are summed per diameter; different diameters are counted separately. The dataset contains no such cases according to the organisers. The existing network is not checked against this rule.

New network branches must be located in heat chambers. In the final scheme, no more than four heating network sections may adjoin a chamber: one toward the source and no more than three in other directions. When selecting an existing chamber as a tie-in point, count both existing and newly added sections.

**Amendment (2026-09-22, organisers' Q&A):** A chamber, a technical node and a tie-in are the same kind of node logically; a chamber has several rays, a tie-in a single new ray. A new chamber is mandatory at every branch point. Several new branches entering one existing chamber are several independent tie-ins, each priced as a tie-in.

Use a technical node where the nominal diameter, installation method, depth, or another section parameter changes, unless that point is already a heat chamber or tie-in point.

## 4. Reference parameters

### 4.1. Nominal diameters, capacity, maximum length, and cost

The table defines permitted nominal diameters, flow capacities, maximum lengths, new construction cost per meter, and reconstruction cost per meter of existing heating network.

| Nominal diameter (mm) | Capacity (t/h) | Maximum length (m) | New construction (UNI/m) | Reconstruction (UNI/m) |
| --- | --- | --- | --- | --- |
| 50 | 3.5 | 181 | 74,023 | 96,180 |
| 65 | 8.3 | 245 | 78,631 | 109,989 |
| 80 | 13.2 | 327 | 83,530 | 117,582 |
| 100 | 22.3 | 419 | 89,748 | 133,694 |
| 125 | 40.2 | 554 | 97,275 | 148,030 |
| 150 | 65.1 | 696 | 105,507 | 152,295 |
| 200 | 152.3 | 1,042 | 120,275 | 181,766 |
| 250 | 274.9 | 1,379 | 135,323 | 202,030 |
| 300 | 437.4 | 1,718 | 150,022 | 228,707 |
| 400 | 943.1 | 2,477 | 190,299 | 271,317 |
| 500 | 1,663.4 | 3,245 | 224,137 | 333,884 |
| 600 | 2,627.7 | 4,037 | 264,790 | 372,703 |
| 700 | 3,735.1 | 4,775 | 324,298 | 439,571 |
| 800 | 5,296.8 | 5,644 | 325,996 | 489,918 |
| 900 | 7,165.0 | 6,518 | 327,693 | 553,607 |
| 1000 | 9,391.8 | 7,419 | 418,777 | 606,679 |
| 1200 | 15,012.8 | 9,288 | 428,074 | 825,692 |
| 1400 | 22,501.9 | 11,276 | 683,417 | 978,584 |

### 4.2. Design dimensions of the new heating network

The new heating network line is treated as the longitudinal axis of the pipe pair's design envelope. The pair's design width accounts for the outer casing diameters and the clear gap between them. The design height equals the outer casing diameter of one pipe.

| Nominal diameter (mm) | Outer casing diameter (m) | Clear gap between casings (m) | Pipe pair design width (m) | Design height (m) |
| --- | --- | --- | --- | --- |
| 50 | 0.125 | 0.150 | 0.400 | 0.125 |
| 65 | 0.140 | 0.150 | 0.430 | 0.140 |
| 80 | 0.160 | 0.150 | 0.470 | 0.160 |
| 100 | 0.180 | 0.150 | 0.510 | 0.180 |
| 125 | 0.225 | 0.150 | 0.600 | 0.225 |
| 150 | 0.250 | 0.150 | 0.650 | 0.250 |
| 200 | 0.315 | 0.250 | 0.880 | 0.315 |
| 250 | 0.400 | 0.250 | 1.050 | 0.400 |
| 300 | 0.450 | 0.250 | 1.150 | 0.450 |
| 400 | 0.560 | 0.250 | 1.370 | 0.560 |
| 500 | 0.710 | 0.250 | 1.670 | 0.710 |
| 600 | 0.800 | 0.250 | 1.850 | 0.800 |
| 700 | 0.900 | 0.250 | 2.050 | 0.900 |
| 800 | 1.000 | 0.250 | 2.250 | 1.000 |
| 900 | 1.100 | 0.250 | 2.450 | 1.100 |
| 1000 | 1.200 | 0.250 | 2.650 | 1.200 |
| 1200 | 1.425 | 0.250 | 3.100 | 1.425 |
| 1400 | 1.600 | 0.250 | 3.450 | 1.600 |

### 4.3. Assumed parameters of existing linear objects

For the optional depth task, use the following assumed existing utility parameters:

| Existing utility | Design envelope | Depth to top of envelope |
| --- | --- | --- |
| Gas pipeline | 0.400 × 0.400 m | 2.8 m |
| Power cable up to 35 kV | 0.200 × 0.200 m | 2.7 m |
| Existing heating network | From Section 4.2, based on its nominal diameter | 3.0 m |

## 5. Spatial restrictions and special crossings

Each spatial restriction type has one of two predefined rules: crossing is prohibited, or a special crossing is permitted.

If crossing is prohibited, the new network must bypass the object while maintaining the specified minimum clearance. If a special crossing is permitted, the crossing portion of the new heating network must be a separate section and satisfy the conditions in Section 5.1.

A special crossing differs from an ordinary section in its crossing conditions and cost. In the output, `laying_method` is `base` for an ordinary section and `special` for a special crossing section. The cost of a special section is additionally multiplied by the special crossing coefficient `K_special` from Section 5.1. For example, a coefficient of 1.60 makes the section cost 1.6 times that of an ordinary section of the same nominal diameter and depth.

For linear restrictions, the input geometry represents the existing object's centerline. Minimum horizontal and vertical clearances are measured between the outer boundaries of the objects' design envelopes. The minimum horizontal clearance applies when running alongside an object.

**Amendment (2026-09-22, organisers' Q&A):** The crossing angle is measured from the obstacle's axis; for polygonal obstacles the team constructs the axis. For polygonal objects the clearance is measured from the outer boundary of the polygon; for linear objects between the outer boundaries of the design envelopes of table 4.3. For roads and tram tracks the special section extends 3 m beyond the boundary or buffer. Rules follow the code of practice on heat networks (SP "Teplovye seti"); the organisers consult on interpretation. Where the zones of several obstacles overlap, the largest coefficient applies to one special section covering the whole overlap; coefficients are never multiplied.

### 5.1. Rules by restriction type

The source table is divided below into horizontal and crossing rules, followed by depth and special section rules. Together, these tables reproduce all conditions from the source.

| Object | Rule | Minimum horizontal clearance | Crossing angle |
| --- | --- | --- | --- |
| Existing OKS | Crossing prohibited | 5 m for new network DN below 500 mm; 7 m for DN 500–800 mm; 9 m for DN 900 mm or greater | Not applicable |
| Park (`park`) | Crossing prohibited | 1.0 m | Not applicable |
| Social facility grounds (`social_area`) | Crossing prohibited | 1.0 m | Not applicable |
| Prohibited area (`prohibited_site`) | Crossing prohibited | 1.0 m | Not applicable |
| Water body (`water`) | Crossing prohibited | 1.0 m | Not applicable |
| Road (`road`) | Special crossing permitted | 1.5 m | At least 45° |
| Tram tracks (`tram_tracks`) | Special crossing permitted | 1.5 m | At least 45° |
| Gas pipeline (`gas_pipeline`) | Crossing permitted using a special crossing | 2.0 m | Not specified |
| Power cable up to 35 kV (`power_cable`) | Crossing permitted using a special crossing | 2.0 m | Not specified |
| Existing heating network (`heat_network`) | Independent crossing without a tie-in permitted using a special crossing | 1.0 m | Not specified |

| Object | Depth rule | Special section boundaries | `K_special` |
| --- | --- | --- | --- |
| Existing OKS | Not applicable | Not applicable | Not applicable |
| Park (`park`) | Not applicable | Not applicable | Not applicable |
| Social facility grounds (`social_area`) | Not applicable | Not applicable | Not applicable |
| Prohibited area (`prohibited_site`) | Not applicable | Not applicable | Not applicable |
| Water body (`water`) | Not applicable | Not applicable | Not applicable |
| Road (`road`) | Below the object; top of the new network design envelope at least 1.0 m below the assumed ground surface | Within the road polygon and extending 3 m beyond its boundary on each side | 1.60 |
| Tram tracks (`tram_tracks`) | Below the object; top of the special section at least 1.2 m below the assumed ground surface | Within the tram track polygon and extending 3 m beyond its boundary on each side | 1.75 |
| Gas pipeline (`gas_pipeline`) | Above or below; minimum vertical clear gap 0.2 m | 2 m on each side of the crossing point | 1.25 |
| Power cable up to 35 kV (`power_cable`) | Above or below; minimum vertical clear gap 0.5 m | 2 m on each side of the crossing point | 1.15 |
| Existing heating network (`heat_network`) | Above or below; minimum vertical clear gap 0.5 m | 2 m on each side of the crossing point | 1.05 |

## 6. Depth-aware routing

The optional depth task assumes a horizontal ground surface and ignores actual terrain. New heating network depth is measured from the assumed ground surface to the top boundary of its design envelope.

The ordinary depth of the new heating network is 3.0 m. The minimum permitted depth is 0.7 m. The depth-related installation cost does not change for depths up to and including 3.0 m.

**Amendment (2026-09-22, organisers' Q&A):** There is no 0.5 m depth step. The 0.7 m value is the minimum vertical clearance from the crossed obstacle, not a minimum burial depth. Nearby crossings may share one dip without returning to 3.0 m between them (closeness criterion pending). Depth and Z are assessed only at the second evaluation stage.

If a crossing requires a change in depth, the profile must include a descending or ascending section, a constant-depth section through the crossing zone, and a section returning to the ordinary depth. The profile must not be reduced to a single deepest or highest point directly at the crossing.

The maximum gradient of a depth transition section is 0.10 m/m: a 1 m depth change requires at least 10 m of horizontal distance. For gas pipeline, power cable, and existing heating network crossings, the constant-depth section is 4 m long, extending 2 m on each side of the crossing point.

### 6.1. Depth cost coefficient

At depths greater than 3.0 m, each additional meter increases the section cost by 10%. The relationship between cost and additional depth is linear.

```text
K_depth = 1 + 0.10 × (h − 3), when h > 3.0 m
K_depth = 1,                  when h ≤ 3.0 m
```

Here, `K_depth` is the depth cost coefficient and `h` is the depth to the top boundary of the new heating network design envelope, in meters.

On a descending or ascending section, depth changes uniformly between the endpoints. Because the relationship between `K_depth` and depth is linear, use the arithmetic mean of the endpoint coefficients:

```text
K_depth_avg = (K_depth_start + K_depth_end) / 2
```

Here, `K_depth_avg` is the average depth cost coefficient for the transition section, and `K_depth_start` and `K_depth_end` are the coefficients at its endpoints.

If a section crosses the 3.0 m depth threshold, create a technical node there, split the section into two parts, and calculate each part's cost separately.

## 7. Reconstruction of the existing heating network

After selecting a tie-in point, check whether the existing network has sufficient capacity all the way to the source. Propagate the additional flow toward the source through the `upstream_object_id` chain and add it to the current design flow of each affected existing network section.

If several new connections use the same existing section, sum their additional flows on the shared portion. If a tie-in lies inside an existing linear section, its additional flow applies only to the portion from the tie-in point toward the source.

For each affected portion of the existing network, calculate:

```text
G_total = G_existing + G_new
```

| Symbol | Meaning | Unit |
| --- | --- | --- |
| `G_total` | Flow after connecting the new OKS | t/h |
| `G_existing` | Current design flow of the existing network | t/h |
| `G_new` | Total additional flow from new connections passing through this network portion | t/h |

Select the smallest suitable nominal diameter for the final flow using Section 4.1. If it is no larger than the existing diameter, reconstruction is unnecessary. If it is larger, reconstruct only the corresponding geometric portion of the existing section without changing its route.

```text
C_reconstruction = L_reconstruction × c_reconstruction(DN_required)
```

| Symbol | Meaning | Unit |
| --- | --- | --- |
| `C_reconstruction` | Reconstruction cost | UNI |
| `L_reconstruction` | Length of the reconstructed portion of the existing network | m |
| `c_reconstruction(DN_required)` | Reconstruction cost per meter at the nominal diameter required after connection, from Section 4.1 | UNI/m |

Special crossing and depth coefficients do not apply to existing network reconstruction. Export each reconstructed portion as a separate line with its actual reconstruction geometry.

## 8. Option cost calculation

An option's calculated cost consists of new heating network sections, construction of new heat chambers, tie-ins, reconstruction of the existing heating network, reconstruction of existing chambers where required, and the penalty for unconnected OKS in Section 8.3.

### 8.1. New heating network sections

For an ordinary section, cost depends on length, nominal diameter, and depth:

```text
C_section = L × c_new(DN) × K_depth
```

| Symbol | Meaning | Unit |
| --- | --- | --- |
| `C_section` | Section cost | UNI |
| `L` | Section length | m |
| `c_new(DN)` | New construction cost per meter for the selected nominal diameter, from Section 4.1 | UNI/m |
| `K_depth` | Depth cost coefficient; equal to 1 for the main two-dimensional task | Dimensionless |

For a special crossing, also apply the coefficient for the crossed object type:

```text
C_section = L × c_new(DN) × K_depth × K_special
```

`K_special` is the special crossing coefficient in Section 5.1. Other symbols have the same meanings as for an ordinary section.

**Amendment (2026-09-22, organisers' Q&A):** A section with a bend at an angle other than 45° or 90° is priced with an additional coefficient of 1.5. Overlapping special zones use the maximum `K_special` once, not the product.

Use one set of calculation parameters for each section. If the nominal diameter, installation method, or applicable cost coefficient changes along a line, split it into separate sections at that point and create a technical node there.

### 8.2. Heat chambers and tie-ins

If the selected tie-in point lies no more than 10 m from an existing heat chamber and no more than four heating network sections would adjoin that chamber after connection, make the tie-in in that chamber. If the section-count condition is not satisfied, or the existing chamber is more than 10 m away, create a new heat chamber at the tie-in point.

Determine the cost of a new heat chamber from the largest nominal diameter of all sections adjoining it in the final option.

If the tie-in is made in an existing chamber, its input `diameter` value is the chamber's original nominal diameter. After forming the option, determine the largest nominal diameter of all adjoining sections, including new sections and the required post-reconstruction diameters of adjoining existing sections.

If that value does not exceed the chamber's original nominal diameter, the chamber does not require reconstruction. If it does, the chamber must be reconstructed to the required nominal diameter.

Count reconstruction of each existing chamber once, regardless of the number of tie-ins into it. Existing chambers that are not used as tie-in points are not reconstructed separately and do not contribute to cost, even if an adjoining existing section is reconstructed.

**Amendment (2026-09-22, organisers' Q&A):** Confirmed: only the affected part of an existing section is reconstructed, and chambers that are not tie-in points are never costed. If several existing chambers lie within 10 m of the tie-in point, the cheapest option is chosen.

Use the same cost schedule for a new chamber and reconstruction of an existing chamber:

| Largest nominal diameter (mm) | Calculated chamber cost (UNI) |
| --- | --- |
| 50–200 | 3,000,000 |
| 250–500 | 5,000,000 |
| 600–1000 | 8,000,000 |
| 1200–1400 | 12,000,000 |

Each independent tie-in costs **5,000,000 UNI**, regardless of nominal diameter.

### 8.3. Unconnected OKS

An option may leave some prospective OKS unconnected. Add the following penalty to calculated cost for each unconnected OKS:

```text
P_OKS = 100,000,000 + 500,000 × G_OKS
```

`P_OKS` is the penalty in UNI, and `G_OKS` is the OKS design flow in t/h.

**Amendment (2026-09-22, organisers' Q&A):** Leaving an OKS unconnected is a legitimate outcome when its route is too expensive; the penalty is the mechanism that decides it.

The fixed component of 100,000,000 UNI accounts for the fact that the OKS is unconnected. The variable component of 500,000 UNI per 1 t/h increases the penalty for higher-flow facilities. The total penalty is the sum of penalties for all unconnected OKS.

## 9. Ranking options

The service may return up to three options. They are compared using two measures: cost contributes 70% of the final score, and the length of linear work contributes 30%.

Because cost is measured in units and length in meters, normalize the measures before applying the weights. The baseline length is 100 m. The baseline cost of 25,000,000 UNI corresponds to an indicative cost of 100 m of heating network at the approximately 250,000 UNI/m unit cost characteristic of this case. Divide cost by 25,000,000 UNI and length by 100 m, then apply weights of 0.7 and 0.3.

```text
S = 0.7 × (C / 25,000,000) + 0.3 × (L / 100)
```

| Symbol | Meaning |
| --- | --- |
| `S` | Final ranking score. Lower values rank higher. |
| `C` | Final option cost in UNI: new heating network sections, new heat chambers, tie-ins, reconstruction of existing linear sections, reconstruction of existing chambers, and the penalty for unconnected OKS. |
| `L` | Total length of linear work in meters: new heating network sections plus reconstructed existing sections. |

**Amendment (2026-09-22, organisers' Q&A):** The organisers restated the weights as 0.7 for length and 0.3 for cost, the reverse of the formula above. Pending written confirmation, the formula above stays the default and the weights remain configurable. Distances may be rounded up when the deviation is negligible (4.999 m counts as 5 m).

## 10. Output data

Return one GeoJSON `FeatureCollection` file in WGS 84 (`EPSG:4326`). All options are placed in the same `features` array and distinguished by `variant_id`.

The output contains new heating network sections, tie-in points, reconstructed portions of the existing heating network, new heat chambers, reconstructed existing heat chambers, technical nodes, and one summary record per option. Do not repeat the prospective OKS connection points in the output.

Each output object type uses only its own mandatory attributes. Do not add fields belonging to other types with `null` values.

**Amendment (2026-09-22, organisers' Q&A):** Whether additional attributes may be added to the output at the first evaluation stage is an open question; until answered, the output carries only the mandatory attributes. Invalid input geometry must be answered with a diagnostic error.

### 10.1. New heating network section

| Attribute | Technical name | Type | Description |
| --- | --- | --- | --- |
| Section identifier | `id` | `string` | Unique section identifier. |
| Object type | `object_type` | `string` | `heat_network`. |
| Option identifier | `variant_id` | `string` | Option ID. |
| Start node ID | `start_node_id` | `string` | ID of a tie-in point, heat chamber, technical node, or OKS connection point. |
| End node ID | `end_node_id` | `string` | ID of the object at the other end of the section. |
| Design flow | `flow_tph` | `number` | Section flow, t/h. |
| Nominal diameter | `diameter` | `integer` | Nominal diameter, mm. |
| Length | `length` | `number` | Section length, m. |
| Installation method | `laying_method` | `string` | Ordinary installation (`base`) or special crossing (`special`). |
| Start depth | `depth_start` | `number / null` | Depth to the top of the design envelope, m; `null` for the main two-dimensional task. |
| End depth | `depth_end` | `number / null` | Depth to the top of the design envelope, m; `null` for the main two-dimensional task. |
| Cost | `cost` | `number` | Section cost, UNI. |

### 10.2. Tie-in point

| Attribute | Technical name | Type | Description |
| --- | --- | --- | --- |
| Tie-in identifier | `id` | `string` | Unique identifier. |
| Object type | `object_type` | `string` | `tie_in`. |
| Option identifier | `variant_id` | `string` | Option ID. |
| Existing object ID | `existing_object_id` | `string` | ID of the existing heating network section or heat chamber where the tie-in is made. |
| Existing object type | `existing_object_type` | `string` | `heat_network` or `heat_chamber`. |
| Existing nominal diameter | `existing_diameter` | `integer` | Existing section diameter, or the chamber's input maximum existing diameter, mm. |
| Required new network nominal diameter | `required_diameter` | `integer` | Nominal diameter of the new heating network at the tie-in point, mm. |
| Cost | `cost` | `number` | Cost of one tie-in, UNI. |

### 10.3. Reconstructed portion of the existing heating network

The object has `LineString` geometry showing exactly the portion of the existing network where the nominal diameter must increase.

| Attribute | Technical name | Type | Description |
| --- | --- | --- | --- |
| Identifier | `id` | `string` | Unique reconstruction object identifier. |
| Object type | `object_type` | `string` | `heat_network_reconstruction`. |
| Option identifier | `variant_id` | `string` | Option ID. |
| Original section ID | `existing_object_id` | `string` | ID of the input `heat_network` object to which the reconstructed portion belongs. |
| Existing flow | `existing_flow_tph` | `number` | Current design flow, t/h. |
| Added flow | `added_flow_tph` | `number` | Total new connection flow on this portion, t/h. |
| Final flow | `calculated_flow_tph` | `number` | Flow after connecting new OKS, t/h. |
| Existing nominal diameter | `existing_diameter` | `integer` | Current nominal diameter, mm. |
| Required nominal diameter | `required_diameter` | `integer` | Smallest suitable diameter after connection, mm. |
| Reconstruction length | `length` | `number` | Length of the reconstructed geometric portion, m. |
| Cost | `cost` | `number` | Reconstruction cost for this existing network portion using the rate in Section 4.1, UNI. |

### 10.4. New heat chamber

| Attribute | Technical name | Type | Description |
| --- | --- | --- | --- |
| Chamber identifier | `id` | `string` | Unique identifier. |
| Object type | `object_type` | `string` | `heat_chamber`. |
| Option identifier | `variant_id` | `string` | Option ID. |
| Nominal diameter | `diameter` | `integer` | Maximum nominal diameter of sections adjoining the chamber in the final option, mm. |
| Cost | `cost` | `number` | Chamber construction cost, UNI. |

### 10.5. Reconstructed existing heat chamber

The object has `Point` geometry and is included only for an existing heat chamber used as a tie-in point when reconstruction is required under Section 8.2.

| Attribute | Technical name | Type | Description |
| --- | --- | --- | --- |
| Identifier | `id` | `string` | Unique reconstruction object identifier. |
| Object type | `object_type` | `string` | `heat_chamber_reconstruction`. |
| Option identifier | `variant_id` | `string` | Option ID. |
| Original chamber ID | `existing_object_id` | `string` | ID of the input `heat_chamber` object being reconstructed. |
| Existing nominal diameter | `existing_diameter` | `integer` | Input nominal diameter of the existing chamber, mm. |
| Required nominal diameter | `required_diameter` | `integer` | Maximum nominal diameter of adjoining sections in the final option, mm. |
| Cost | `cost` | `number` | Existing chamber reconstruction cost under the schedule in Section 8.2, UNI. |

### 10.6. Technical node

| Attribute | Technical name | Type | Description |
| --- | --- | --- | --- |
| Node identifier | `id` | `string` | Unique identifier. |
| Object type | `object_type` | `string` | `technical_node`. |
| Option identifier | `variant_id` | `string` | Option ID. |

### 10.7. Option summary

For each option, include exactly one summary record in `features` with `geometry = null`. It is not a spatial object and contains only the option's aggregate results.

| Attribute | Technical name | Type | Description |
| --- | --- | --- | --- |
| Summary record identifier | `id` | `string` | Unique identifier. |
| Object type | `object_type` | `string` | `variant_summary`. |
| Option identifier | `variant_id` | `string` | Option ID. |
| Rank | `rank` | `integer` | 1 identifies the best option. |
| Linear construction cost | `construction_cost` | `number` | Cost of new heating network sections, UNI. |
| Chamber construction cost | `chamber_construction_cost` | `number` | Cost of new heat chamber construction, UNI. |
| Tie-in cost | `tie_in_cost` | `number` | Total cost of tie-ins to the existing heating network, UNI. |
| Linear reconstruction cost | `reconstruction_cost` | `number` | Cost of reconstructing existing linear sections, UNI. |
| Chamber reconstruction cost | `chamber_reconstruction_cost` | `number` | Cost of reconstructing existing heat chambers requiring reconstruction, UNI. |
| Unconnected OKS penalty | `unconnected_penalty` | `number` | Total penalty, UNI. |
| Final cost | `calculated_cost` | `number` | New linear sections + new chambers + tie-ins + linear reconstruction + chamber reconstruction + unconnected OKS penalty, UNI. |
| New network length | `new_network_length` | `number` | Total length of new linear sections, m. |
| Reconstruction length | `reconstruction_length` | `number` | Total length of reconstructed linear portions, m. |
| Total length | `length` | `number` | New network length plus reconstruction length, m. |
| Final score | `score` | `number` | `S` from Section 9. |
| Unconnected OKS | `unconnected_oks_ids` | `array[string]` | IDs of OKS for which no route was found. |

### 10.8. Example output GeoJSON

The following is an abbreviated example of one option's structure. Coordinates and numerical values are illustrative; the example shows field meanings and relationships between objects.

**Translation note:** The source example is reproduced without changing its values. It is a structural illustration, not a validated calculation fixture; its stated costs and score do not all exactly match the reference formulas and rates. For the mandatory two-dimensional task, Section 10.1 requires `depth_start` and `depth_end` to be `null`, although the source example supplies numeric depths.

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometry": {
        "type": "LineString",
        "coordinates": [
          [
            37.6,
            55.75
          ],
          [
            37.601,
            55.751
          ]
        ]
      },
      "properties": {
        "id": "new_1",
        "object_type": "heat_network",
        "variant_id": "1",
        "start_node_id": "tie_1",
        "end_node_id": "node_1",
        "flow_tph": 80.0,
        "diameter": 200,
        "length": 145.2,
        "laying_method": "special",
        "depth_start": 3.0,
        "depth_end": 3.0,
        "cost": 27942288
      }
    },
    {
      "type": "Feature",
      "geometry": {
        "type": "Point",
        "coordinates": [
          37.6,
          55.75
        ]
      },
      "properties": {
        "id": "tie_1",
        "object_type": "tie_in",
        "variant_id": "1",
        "existing_object_id": "net_12",
        "existing_object_type": "heat_network",
        "existing_diameter": 150,
        "required_diameter": 200,
        "cost": 5000000
      }
    },
    {
      "type": "Feature",
      "geometry": {
        "type": "LineString",
        "coordinates": [
          [
            37.599,
            55.749
          ],
          [
            37.6,
            55.75
          ]
        ]
      },
      "properties": {
        "id": "recon_1",
        "object_type": "heat_network_reconstruction",
        "variant_id": "1",
        "existing_object_id": "net_12",
        "existing_flow_tph": 100.0,
        "added_flow_tph": 80.0,
        "calculated_flow_tph": 180.0,
        "existing_diameter": 150,
        "required_diameter": 250,
        "length": 75.0,
        "cost": 15152250
      }
    },
    {
      "type": "Feature",
      "geometry": {
        "type": "Point",
        "coordinates": [
          37.601,
          55.751
        ]
      },
      "properties": {
        "id": "node_1",
        "object_type": "technical_node",
        "variant_id": "1"
      }
    },
    {
      "type": "Feature",
      "geometry": {
        "type": "Point",
        "coordinates": [
          37.602,
          55.752
        ]
      },
      "properties": {
        "id": "ch_1",
        "object_type": "heat_chamber",
        "variant_id": "1",
        "diameter": 200,
        "cost": 3000000
      }
    },
    {
      "type": "Feature",
      "geometry": null,
      "properties": {
        "id": "summary_1",
        "object_type": "variant_summary",
        "variant_id": "1",
        "rank": 1,
        "construction_cost": 27942307,
        "chamber_construction_cost": 3000000,
        "tie_in_cost": 5000000,
        "reconstruction_cost": 15152283,
        "chamber_reconstruction_cost": 0,
        "unconnected_penalty": 0,
        "calculated_cost": 51094590,
        "new_network_length": 145.2,
        "reconstruction_length": 75.0,
        "length": 220.2,
        "score": 2.09124852,
        "unconnected_oks_ids": []
      }
    }
  ]
}
```
