# General Technical Specification for a District Heating Connection Routing Service

> Amended 2026-09-22 with the organisers' answers (see [CHANGELOG.md](CHANGELOG.md)). Amendments are marked inline and take precedence over the original text where they differ. The runtime-stack agreement (item 1 of the Q&A) is deliberately not incorporated here.

Terminology: **OKS** is the source abbreviation for a capital construction facility, such as a building. **Prospective OKS** means a planned facility to be connected. **Nominal diameter** means the standardized pipe size, in millimeters. A **tie-in point** is where the new network connects to the existing network. A **heat chamber** is an underground structure containing pipeline equipment and connections. Flow is expressed in metric tonnes per hour (`t/h`); costs are in units.

## 1. Background and objective

Connecting a facility to a district heating network is part of the utility connection process. For each new facility, the connection location and the route of the heating network must be determined within a densely built urban environment.

The design accounts for each building's heat demand. When several facilities share a connection, network sections supplying multiple buildings must account for their combined demand. This determines the required pipeline diameter.

The route must also account for the existing urban environment. Natural areas and green spaces, existing buildings and structures, roads, metro lines, and other underground utilities may lie along the route. Different objects have different restrictions and crossing conditions.

When several prospective OKS are connected at once, the number of possible options grows rapidly. Facilities can be connected individually or jointly, using different tie-in locations and routes. Manually finding and comparing these options requires substantial effort. Automation accelerates this work.

### 1.1. How an engineer develops a connection option

Heat is conveyed through two pipelines. Heated water travels from the source to the consumer through the supply pipeline and returns to the source through the return pipeline. On a map, this pair is usually represented by one line. Both pipelines within a section have the same nominal diameter.

First, the engineer determines where the new network should approach the OKS. The individual heat substation, through which the building connects to the heating system, is located inside the building. Preliminary routing only requires a connection point on the OKS boundary; the substation's position inside the building does not need to be specified. The competition dataset supplies the connection point as input.

Next, the engineer selects the tie-in point where the new network connects to the existing network. A tie-in can be made in an existing heat chamber or directly into a pipeline. If no suitable chamber exists at the selected location, a new one may need to be built.

The route from the existing network to the facilities is then determined. Multiple OKS do not necessarily need separate routes: they can share a network section that later branches into separate directions.

The engineer accounts for existing development, roads, natural features, and utilities. Some obstacles must be bypassed, some may be crossed subject to specified requirements, and others require special engineering solutions.

After the route is established, the heat-transfer fluid flow is determined for each section. A section serving several OKS carries their combined flow. Higher flow may require a larger nominal diameter.

Route length is not the only comparison criterion. A shorter route may be more complex or more expensive because of an unsuitable tie-in location, obstacles, or additional structures.

### 1.2. Automation objective

Develop a service that automatically processes all prospective OKS, generates new heating network options, selects tie-in points, calculates flows, selects nominal diameters, determines the required reconstruction of the existing network, and compares the resulting solutions.

The service must produce its result without manual correction of the generated routes.

## 2. Task description

### 2.1. Required service

Develop a service that automatically generates options for connecting prospective OKS to an existing district heating network.

The service receives one combined GeoJSON file containing the existing heating network, heat chambers, prospective and existing OKS, connection points for prospective OKS, and spatial restrictions. Each object type has its own mandatory attributes.

A technical appendix is supplied with the case and contains formal rules, reference values, and calculation parameters.

The input may contain:

- Existing buildings and areas where heating network placement is restricted.
- Natural areas and green spaces.
- Roads.
- Tram and railway tracks.
- Metro infrastructure.
- Rivers and other water obstacles.
- Gas pipelines.
- Power cables.
- Water supply, sewerage, and other utilities.
- Supports, structures, and other urban infrastructure.

The specific competition dataset may contain only some of these types. A handling rule is specified in advance for every type included in the dataset.

**Amendment (2026-09-22, organisers' Q&A):** The competition dataset gives a concrete connection point per prospective OKS; the OKS geometry itself does not affect the solution. The dataset is city-scale (thousands of objects). Some information is removed for security reasons (critical infrastructure). The realistic input volume is up to 2 to 3 GB. Invalid input geometry must produce a diagnostic error rather than a result. Handling additional obstacle kinds (metro, kindergartens, schools, water bodies, tram lines) and keeping the algorithm generic for other resources (water supply, cables) earns extra credit.

The service determines which OKS to connect jointly, builds the new network, selects tie-in points, calculates flows, selects nominal diameters, identifies the required reconstruction of the existing network, and compares options.

The team chooses the solution method. Graph algorithms, mathematical optimization, computational geometry, heuristics, or combinations of these may be used.

### 2.2. Baseline model

The task uses the following simplifications:

- There is one heat source. Its capacity limit is not considered.
- The existing network is connected to the source.
- Each existing section includes its nominal diameter, existing design flow, and the ID of the next existing object toward the source. This chain may pass through network sections and chambers and terminates at the source.
- Each prospective OKS includes its heat load and a precalculated design flow in `t/h`. The flow is used to calculate the new network.
- One line on the map represents a supply/return pipeline pair with the same nominal diameter.
- Each separate connected component of the new network connects to the existing network at one point.
- There must be exactly one path from the tie-in point to each connected OKS. The network must not form a loop that allows a facility to be reached from two directions.
- For this case, the entire designed network is treated as a trunk network. No separate “building service connection” section type is distinguished.
- The mandatory task uses two-dimensional routing. Depth is considered in a separate optional task.
- The baseline installation method is underground direct burial, with pipes placed directly in the ground without a duct.
- Aboveground installation is excluded.
- The nominal diameter of the existing network does not decrease toward the source. In the competition model, its flow capacity is also assumed not to decrease toward the source. A full hydraulic calculation is not performed.

### 2.3. New network structure

The new network may connect several prospective OKS. Shared sections carry the total flow of the facilities supplied through them. After a branch, the flow in each outgoing direction decreases.

Branches must be located in heat chambers. No more than four heating network sections may adjoin a chamber: one toward the source and no more than three in other directions. Existing incident sections must be counted when selecting an existing chamber as a tie-in point.

If the tie-in is not made in an existing heat chamber under the technical appendix rules, a new heat chamber must be provided at the tie-in point.

**Amendment (2026-09-22, organisers' Q&A):** There is no logical difference between a heat chamber, a technical node and a tie-in: a chamber is a node with several rays, a tie-in is a node with a single new ray. A new heat chamber is mandatory at every branch point. If the tie-in point lies within 10 m of an existing chamber the network enters that chamber; if several existing chambers lie within 10 m, the cheapest is chosen; farther than 10 m a new tie-in is built. Several new branches entering one chamber count as several independent tie-ins.

The route may also contain technical nodes: auxiliary points where the nominal diameter, installation method, or another section parameter changes. A technical node is not a heat chamber and has no separate cost of its own.

New sections consist of straight segments connected by turns. Route geometry must be rational and must not contain unjustified bends, zigzags, or stair-step fragments.

**Amendment (2026-09-22, organisers' Q&A):** Bends at angles other than 45° or 90° carry a 1.5 times cost surcharge on the affected section, and the number of turns must be minimised. Distances may be rounded up when the deviation is negligible (4.999 m counts as 5 m).

New sections must not intersect one another outside a shared node. If two routes intersect, they must be merged into a common network or rerouted.

### 2.4. Flows and nominal diameters

The design flow of each prospective OKS is supplied as a precalculated value in `t/h`.

If a new section supplies several OKS, their flows are added together. The supply and return pipelines have the same flow, so only the supply pipeline flow is used in the calculation.

For each section, select the smallest nominal diameter that accommodates its design flow. Permitted diameters and their corresponding capacities are defined in the technical appendix.

In a real heating network, hydraulic losses depend on factors including pipeline diameter and length. Smaller diameters and greater lengths increase hydraulic resistance and pressure losses.

The case does not require a full hydraulic calculation. Instead, each nominal diameter has a maximum permitted length for a continuous portion of the new network.

As long as the nominal diameter remains unchanged, the lengths of consecutive sections are counted together. Installing a heat chamber or a technical node does not itself restart the count. A change in nominal diameter starts a new count.

**Amendment (2026-09-22, organisers' Q&A):** When the maximum length of a continuous same-diameter portion is exceeded, the diameter may be raised by exactly one size in the table (for example 200 to 250) and no more. At a branch without a diameter change the lengths are summed per diameter across the branches; portions of different diameters are counted separately. The organisers state that the competition dataset contains no such cases. The existing network is not checked against this rule.

### 2.5. Existing network checks and reconstruction

The service must check whether the existing network can accommodate the additional flow from new consumers. A full hydraulic calculation of the existing network is not required; a simplified reconstruction model based on flows and nominal diameters is used.

The additional flow from each tie-in point is added to the existing design flow of the section receiving the tie-in and then to every existing network section toward the source. If an existing section carries additional flow from several new connections, those flows are summed.

For each affected existing section, select the smallest suitable nominal diameter based on its final flow, using the same capacities as for the new network.

If the required nominal diameter is no larger than the existing diameter, reconstruction is unnecessary. If it is larger, the section must be reconstructed to the required nominal diameter.

In the competition model, reconstruction does not change the existing network geometry or generate a new route for it. Reconstruction calculations use the existing section geometry. The technical appendix defines the cost rules.

If the tie-in is made in an existing heat chamber, the need to reconstruct that chamber must also be checked under the technical appendix rules.

**Amendment (2026-09-22, organisers' Q&A):** Only the affected part of an existing section is reconstructed. Existing chambers that are not tie-in points are never reconstructed and are not costed.

### 2.6. Spatial restrictions

For each spatial restriction type, the technical appendix defines how to handle it: a prohibition or permitted crossing conditions, minimum clearances, crossing angle, and special crossing requirements.

Depending on the object, the new network must:

- Bypass it.
- Maintain a minimum clearance.
- Cross it under specified conditions.
- Cross it using a special crossing.

For a special crossing, the corresponding part of the route must be represented as a separate new network section with special installation conditions.

The input specifies each restriction's type and geometry. The service applies the rule defined for that type in the technical appendix.

### 2.7. Cost

Cost is used to compare options. It includes:

- New network sections.
- New heat chambers.
- Tie-ins to the existing network.
- Crossings of spatial restrictions.
- Reconstruction of existing heating network sections whose capacity is insufficient after the new OKS are connected.
- Reconstruction of existing heat chambers used for tie-ins, where required.

The cost of a new section depends on its length, nominal diameter, and installation method. If a different installation method is used to cross an obstacle along part of the route, the special crossing cost calculation applies to that part.

Specific values and calculation rules are defined in the technical appendix.

### 2.8. Options and ranking

The service produces a primary option and may offer up to two additional, meaningfully different options.

An option is considered different if, for example, it changes:

- The tie-in point.
- The grouping of OKS into a shared network.
- The route.
- The division into separate connected components of the new network.
- The method of crossing a restriction, where several solutions are permitted.

A small shift of the same route does not count as a separate option.

All generated options are considered in one list. An option's calculated cost includes new network sections, new heat chambers, tie-ins, reconstruction of existing linear sections and, where necessary, existing heat chambers, plus a penalty for prospective OKS for which no route was found.

Options are ranked using a score weighted 70% for calculated cost and 30% for the total length of linear construction work. Length includes both new and reconstructed heating network sections. Lower scores rank higher. The technical appendix provides the formula.

**Amendment (2026-09-22, organisers' Q&A):** The organisers restated the criterion as length and cost with weights 0.7 and 0.3, in that order, which inverts the appendix formula. Until the organisers confirm in writing which quantity carries 0.7, the appendix formula (cost 0.7, length 0.3) remains the implemented default and the weights stay a run parameter.

### 2.9. When no automatic route is found

The competition dataset is prepared so that every prospective OKS can be connected while complying with the case rules.

Nevertheless, the service must handle cases where it fails to find an automatic route for an individual OKS. It must retain the result built for the remaining facilities and separately report the IDs of prospective OKS for which no automatic route was found.

Failure to find an automatic route does not mean that connecting the facility is technically impossible in principle.

**Amendment (2026-09-22, organisers' Q&A):** Leaving a prospective OKS unconnected is permitted when its route is too expensive; the penalty of the technical appendix then applies and the trade-off is part of the optimisation.

### 2.10. Service output

The service must provide:

- New network sections with flow, nominal diameter, length, installation method, and cost.
- Tie-in points.
- New heat chambers.
- Technical nodes at changes in section parameters.
- Summary information and total cost for each option.
- IDs of prospective OKS for which no automatic route was found, if any.
- Existing heating network sections requiring reconstruction, including original and required nominal diameters, additional flow, length, and calculated reconstruction cost.
- Existing heat chambers requiring reconstruction, including original and required nominal diameters and calculated reconstruction cost.
- An export in one combined GeoJSON file with the mandatory attributes.

The detailed output structure is defined in the technical appendix.

### 2.11. Mandatory functionality

The service must:

- Process all prospective OKS in one run.
- Automatically select tie-in points.
- Determine joint or separate connections for the OKS and build the new network.
- Comply with network formation rules, spatial restrictions, and route geometry requirements.
- Calculate flows and select nominal diameters.
- Check the maximum length of continuous portions with the same nominal diameter.
- Propagate additional flows from tie-in points through the existing network toward the source; determine required nominal diameters and sections requiring reconstruction; and determine whether existing chambers used for tie-ins require reconstruction.
- Calculate costs and produce up to three meaningfully different options.
- Preserve partial results when an automatic route is not found for an individual OKS.
- Export results in the required format.

### 2.12. Scope exclusions

The competition does not require:

- A full hydraulic calculation, including pressures, head losses, heat-transfer fluid velocities, pump operating conditions, and network balancing.
- Calculation or expansion of heat source capacity.
- Depth modeling or a longitudinal profile in the mandatory task.
- Detailed engineering design or structural strength calculations.
- Structural design of heat chambers and special crossings.
- Thermal expansion calculations or expansion compensator selection.
- Preparation of design documentation or construction organization plans.
- Aboveground installation.
- Determination of the individual heat substation's location inside the building.
- Changes to the existing network route during reconstruction or new spatial design solutions for it.

Routes, tie-in points, and branches must be generated automatically. Manual correction of generated geometry before demonstrating the result is prohibited.

### 2.13. Optional task for depth-aware routing

In addition to mandatory two-dimensional map routing, the team may implement a separate mode that accounts for depth and the new network's vertical position relative to existing utilities.

This is an independent task: the service generates a new route and may choose a different plan route if it is preferable when depth is considered.

Detailed rules are provided in the appendix “Depth-Aware Heating Network Routing,” translated below.

### 2.14. Generality of the solution

Coordinates, identifiers, and the specific configuration of the competition dataset must not be hardcoded.

The service will be tested on a separate dataset with the same structure. Processing it must not require changing the algorithm or preparing routes manually.

## 3. Hardware and software requirements

### 3.1. Hardware requirements

RAM: 16 GB. No other hardware requirements are specified.

### 3.2. Software requirements

| Area | Requirement |
| --- | --- |
| Operating system | Deploy the service on Ubuntu Server 22. |
| Runtime and build | Java 11; build based on `spring-boot-starter-parent` 2.6.3, meaning Spring Boot 2.6.3. |
| Database and search engine | Use PostgreSQL, versions up to and including 18, or OpenSearch, versions up to and including 2.18, as the primary database. Amendment (2026-09-22): PostGIS is permitted. |
| API documentation | Use `springdoc-openapi-ui` 1.7.0, with Swagger UI, to generate and display API documentation. |
| Containerization and deployment | Build and deploy through `docker-compose` 1.29.2. All required services, including the application and databases, must be defined and started through `docker-compose`. |
| File handling | Support input uploads up to 3 GB and result exports up to 500 MB. Process large files using streaming and temporary disk storage without loading the entire file into RAM. |
| User load | Support up to 50 users. |
| Deployment shape (amendment 2026-09-22) | A single instance on a single server at the first stage; horizontal scaling may be designed in but is not required. No CPU limits are stated. No limit on processing time per file, but speed is an additional evaluation criterion. The service runs fully offline: no external agents, no API tokens. |
| Delivery (amendment 2026-09-22) | The team hands over a repository with sources and deployment scripts (Maven and Gradle); the experts deploy and test on their own hardware. For the defence the team may deploy on its own. |
| User interface (amendment 2026-09-22) | No UI is required: the service is input data in, GeoJSON out. Visualisation quality is not assessed; GeoJSON correctness is. Swagger and API documentation are desirable. A separate frontend container is optional and not assessed. |

## 4. Presentation and demonstration requirements

1. Upload the competition dataset into the service.
2. Run processing for all prospective OKS without manually preparing routes.
3. Export the result from the service.
4. Show the generated network, connection and tie-in points, flows, nominal diameters, maximum-length checks, existing network reconstruction sections, and cost.
5. Demonstrate handling of spatial restrictions.
6. Show how new connections change design flows in the existing network and which sections require increased nominal diameters.
7. If no automatic route was found for certain OKS, show their list.
8. Compare the resulting options and explain their ranking.
9. Briefly explain the algorithm and its applicability limits.
10. If the optional task is implemented, demonstrate a separate depth-aware calculation.

## 5. Supporting documentation requirements

1. Describe the route search and joint OKS connection algorithm.
2. Describe how OKS without an automatically found route are handled.
3. Describe the output data.
4. If the optional task is implemented, describe the depth-aware routing algorithm.
5. Describe the solution's applicability limits.

## 5a. Open items awaiting the organisers (2026-09-22)

- Plan-view grid cell size: the specification mentioned 3 m, the technical appendix fixes nothing. Answer promised.
- Whether the output GeoJSON may carry additional attributes at the first stage (for instance attributes of a conditional hydraulic model). Answer promised.

## 6. Resources

| Resource | Contents |
| --- | --- |
| Competition dataset | Geospatial data for developing and demonstrating the solution. |
| Technical appendix | Formal service rules, reference values, calculation parameters, and result structure. See [route_rules.md](route_rules.md). |
| Optional depth task | Separate appendix with depth calculation rules, translated below. |

## 7. Submission requirements

### 7.1. Interim submission

1. A working prototype for loading input data.
2. Automatic generation of at least part of the network.
3. A brief list of implemented functionality, risks, and the plan for completion before the final submission.

### 7.2. Final submission

1. A working service.
2. A presentation.
3. Supporting documentation.
4. An exported result for the competition dataset.

## 8. Evaluation criteria

Experts first check compliance with mandatory rules: correct new network construction, flow and nominal diameter calculations, maximum length, spatial restrictions, and output data.

**Amendment (2026-09-22, organisers' Q&A):** Evaluation has two stages. The first compares the horizontal projections of the generated routes with reference geometry; Z coordinates and depth calculations are assessed only at the second stage, for up to ten of the most interesting solutions. Additional justified cost criteria, variety of handled obstacles and universality of the algorithm for other resources count in favour.

| Criterion | What experts assess |
| --- | --- |
| Team approach | Justification for the route search method, joint OKS connections, and tie-in selection. |
| Routing quality | Rational routes, number of turns, absence of arbitrary small-segment polylines and unjustified detours. |
| Compliance with the task | Processing prospective OKS; calculating flows and diameters for new and existing networks; identifying reconstruction; complying with restrictions; and producing the result. |
| Solution efficiency | Option costs, justification for the number of tie-ins and chambers, effectiveness of joint OKS connections, and the extent of required reconstruction. |
| Technical implementation | Calculation stability, data diagnostics, and operation on an additional dataset without changing the algorithm. |
| Options and explainability | Meaningfully different options and understandable ranking. |
| Additional value | Useful features beyond the mandatory requirements. |
| Pitch session | Clarity of the demonstration and answers to questions. |

## Appendix A. Depth-Aware Heating Network Routing

Optional task for the “Leaders of Digital Transformation” 2026 competition case.

### A.1. Purpose of the optional task

The main task builds the new heating network route in a plane. The optional task builds a depth-aware route: the new network must both avoid restrictions on the map and pass correctly above or below existing utilities.

This is an independent task, not a check of a route already generated solely in two dimensions. The plan route may change if another path provides simpler and cheaper vertical crossings of existing utilities.

All main-case rules remain applicable, including selection of an optimal tie-in point, nominal diameter selection, the maximum permitted length of a continuous network portion with one nominal diameter, and heat chamber requirements.

### A.2. Required work

Build a connection scheme for prospective OKS that accounts for both plan position and depth. The service selects the route, depths of new sections, crossing methods, and cost including the effect of greater burial depth.

Use an assumed horizontal ground surface at `Z = 0`. The lower the network is located, the greater its depth and the smaller its Z value.

### A.3. Simplifications and assumptions

1. **Ground surface.** Terrain is ignored. The ground surface is assumed horizontal throughout the area.
2. **Existing utility depths.** Actual elevations are unavailable. One assumed depth for each utility type is supplied with the case.
3. **Crossings between existing utilities.** At the assumed depths, existing utilities, including existing heating networks, may intersect at the same level. Their relative positions are not checked. Only the new heating network's position relative to existing utilities is checked.
4. **Vertical crossing rules.** A ready-made reference table specifies permitted positions and minimum clearances for different utility types.
5. **New heating network envelope.** A design width and height are specified for each nominal diameter and installation method. These are used to check whether the network can pass above or below an existing utility.
6. **Geology and groundwater.** These are ignored. Soil is assumed uniform and suitable for installation throughout the area.

### A.4. Depth rules

1. The reference data specify the minimum permitted depth for each nominal diameter and installation method.
2. Depth is selected in increments of 0.5 m. Minimum and maximum depths are parameters.

**Amendment (2026-09-22, organisers' Q&A):** The current appendix defines no 0.5 m step. The minimum of 0.7 m is the minimum vertical clearance from the crossed obstacle; the ordinary depth is 3 m; the surcharge applies per additional unit of depth. Nearby crossings may be merged into a single dip without returning to 3 m between them; the closeness criterion is to be agreed in working order.
3. At a crossing, the new heating network must pass above or below the existing utility according to the reference rules and maintain the specified clearance.
4. If both above and below crossings are allowed, the service chooses the option with the lower total cost.
5. Transitions between depths use straight inclined sections. A full longitudinal profile calculation and structural design of the transition are not required.
6. If no permitted route is found within the specified depth range, the affected OKS or route portion is marked as requiring manual engineering review.

### A.5. Cost

Greater burial depth increases the calculated network cost. A ready-made cost or coefficient table with 0.5 m depth increments is supplied with the case.

Apply the value corresponding to the depth of each route portion. Other cost rules remain the same as in the main case.

### A.6. Additional input data

1. Assumed depth for each existing utility type.
2. Assumed depth of the existing heating network.
3. Reference rules for vertical crossings and minimum clearances.
4. New network envelope reference data: nominal diameter, installation method, design width, design height, and minimum depth.
5. Cost or coefficient table by depth in 0.5 m increments.
6. Minimum and maximum depths available to the search.
7. Mandatory attributes for input and output entities.

### A.7. Optional task output

1. A new heating network route generated with depth taken into account.
2. The depth of each new section and Z coordinates in its geometry.
3. Crossing locations with existing utilities, indicating whether the new network passes above or below.
4. Calculated vertical clearances at crossings.
5. Route cost accounting for depth.
6. Conflicts and OKS requiring manual engineering review.
7. GeoJSON with Z coordinates and all mandatory attributes populated.

### A.8. Optional task scope exclusions

The optional task does not require:

- Actual terrain or actual elevations of existing utilities.
- Checks of crossings between existing utilities.
- Geology and groundwater modeling.
- A complete longitudinal profile, or calculations of drainage, air vents, and thermal expansion.
- Structural design of vertical transitions.
- Detailed engineering design or confirmation of construction feasibility based on actual engineering surveys.
