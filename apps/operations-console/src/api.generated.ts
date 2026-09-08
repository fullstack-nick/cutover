export interface paths {
    "/api/v1/sites/{siteId}/orders": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        get: operations["listOrders"];
        put?: never;
        /** Durably accept synthetic work; acceptance is not completion */
        post: operations["createOrders"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/orders/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get: operations["getOrders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/return-receipts": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        get: operations["listReturnReceipts"];
        put?: never;
        /** Durably accept synthetic work; acceptance is not completion */
        post: operations["createReturnReceipts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/return-receipts/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get: operations["getReturnReceipts"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/tasks": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        get: operations["listTasks"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/equipment": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        get: operations["getEquipment"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/commands/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get: operations["getCommand"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/commands": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        /** Read up to 100 site-scoped uncertain or quarantined commands, oldest first */
        get: operations["listRecoveryCommands"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/commands/{id}/reconciliation": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Supervisor records a versioned status investigation using the original command ID
         * @description Requires the supervisor role and a reason. A successful response records a request; it is not physical retry authority or proof of completion. A live lease, terminal state or changed version returns 409.
         */
        post: operations["investigateCommand"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/orders/{id}/cancellation": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Supervisor cancels an entirely unstarted order
         * @description expectedVersion is the order version. The durable intent holds dispatch while the adapter fences the complete movement inventory. A 200 response confirms exactly one reservation release; 409 indicates a version or unsafe-movement conflict. A 503 CANCELLATION_PENDING means the intent remains durable; inspect the order. Reuse the identical request/key after a transport interruption.
         */
        post: operations["cancelOrder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/orders/{id}/cancellations/{cancellationId}/retry": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
                cancellationId: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Supervisor resumes a paused cancellation after repair
         * @description expectedVersion is the cancellation version. Only PAUSED may resume. The 200 response records a new bounded retry budget for the same intent; poll the order for the final outcome. The original browser request key is not required.
         */
        post: operations["resumeCancellation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/shadow-comparisons": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        /** @description Operator/supervisor read of the isolated shadow evidence database; latest 50 summaries and total/mismatch counts. */
        get: operations["listShadowComparisons"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/shadow-comparisons/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get: operations["getShadowComparison"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/tasks/{id}/recovery": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Supervisor resumes six exhausted transport observations with expected version and reason. Retains movement/allocation/command identities. */
        post: operations["recoverLegacyTask"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/execution-tasks": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        /** @description Operator/supervisor read of the independent execution task owner; at most 100 tasks ordered by characterized priority, eligibility and stable movement ID. */
        get: operations["listExecutionTasks"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/execution-tasks/{id}/recovery": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Supervisor resumes an exhausted execution transport investigation with expected version and reason. No allocation or physical command identity is replaced. */
        post: operations["recoverExecutionTask"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/zones": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        /** @description Site-scoped operator/supervisor observation. Retained sessions are bounded; observation times identify stale data. */
        get: operations["listZoneRoutes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/migrations": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        /** @description Site-scoped operator/supervisor observation. Retained sessions are bounded; observation times identify stale data. */
        get: operations["listMigrations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/migrations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        /** @description Site-scoped operator/supervisor observation. Retained sessions are bounded; observation times identify stale data. */
        get: operations["getMigration"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/zones/{zoneId}/migrations": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                zoneId: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Supervisor starts drain with an expected route version and reason. New intents remain unassigned; all allocated work and physical evidence must reconcile before an atomic epoch change. A reversal is another complete drain. */
        post: operations["startMigration"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/migrations/{id}/recovery": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Supervisor resumes the same session after six exhausted owner-evidence transport attempts. Expected session version and reason are required. No physical identity or proof is replaced. */
        post: operations["recoverMigration"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/migrations/{id}/cancellation": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Supervisor cancels only an unswitched session with the expected session version and reason. Existing owner/epoch remain; pending unassigned work is released. After switching, use a new reverse migration. */
        post: operations["cancelMigration"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/return-counters": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        get: operations["getReturnCounters"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/return-tasks": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        get: operations["getReturnTasks"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sites/{siteId}/return-tasks/{id}/recovery": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Resume an exhausted returns transport investigation; physical uncertainty remains under adapter reconciliation
         * @description Supervisor resumes an exhausted execution transport investigation with expected version and reason. No allocation or physical command identity is replaced.
         */
        post: operations["recoverReturnTask"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        "equipment-command": components["schemas"]["equipment-command.v1"];
        "event-envelope": components["schemas"]["event-envelope.v1"];
        "migration-request": components["schemas"]["migration-request.v1"];
        movement: components["schemas"]["movement.v1"];
        "order-request": components["schemas"]["order-request.v1"];
        "receipt-request": components["schemas"]["receipt-request.v1"];
        "reconciliation-request": components["schemas"]["reconciliation-request.v1"];
        problem: {
            type: string;
            title: string;
            status: number;
            code: string;
            detail?: string;
        };
        accepted: {
            /** Format: uuid */
            id: string;
            statusUrl: string;
        };
        "order-view": components["schemas"]["order-view.v1"];
        "order-page": components["schemas"]["order-page.v1"];
        "task-list": components["schemas"]["task-list.v1"];
        "equipment-view": components["schemas"]["equipment-view.v1"];
        "command-view": components["schemas"]["command-view.v1"];
        "cancellation-certificate": components["schemas"]["cancellation-certificate.v1"];
        "scheduling-comparison": {
            /** Format: uuid */
            roundId: string;
            inputHash: string;
            input: {
                /** @constant */
                ruleVersion: 1;
                siteId: string;
                /** @enum {unknown} */
                zoneId: "ambient" | "chilled";
                /** Format: date-time */
                decisionAt: string;
                /** Format: date-time */
                observedAt: string;
                topologyVersion: number;
                worldMismatch: boolean;
                route: {
                    /** @enum {unknown} */
                    owner: "legacy-core" | "execution-service";
                    epoch: number;
                    /** @enum {unknown} */
                    state: "ACTIVE" | "DRAINING" | "RECONCILIATION_REQUIRED";
                } & {
                    [key: string]: unknown;
                };
                candidates: ({
                    /** Format: uuid */
                    movementId: string;
                    priority: number;
                    /** Format: date-time */
                    eligibleAt: string;
                    /** @enum {unknown} */
                    zoneId: "ambient" | "chilled";
                    owner: string | null;
                    epoch: number | null;
                    /** @enum {unknown} */
                    allocationState: "ASSIGNED" | "PENDING" | "COMPLETED" | "CANCELLED";
                    commandState: string | null;
                } & {
                    [key: string]: unknown;
                })[];
                lanes: ({
                    siteId: string;
                    /** @enum {unknown} */
                    zoneId: "ambient" | "chilled";
                    laneId: string;
                    blocked: boolean;
                } & {
                    [key: string]: unknown;
                })[];
            } & {
                [key: string]: unknown;
            };
            legacyProposal: {
                /** @constant */
                ruleVersion: 1;
                /** Format: uuid */
                selectedMovementId: string | null;
                selectedLaneId: string | null;
                ranking: ({
                    /** Format: uuid */
                    movementId: string;
                    /** @enum {unknown} */
                    reason: "READY" | "WORLD_MISMATCH" | "OBSERVATION_STALE" | "ROUTE_UNCERTAIN" | "ZONE_MISMATCH" | "OWNER_MISMATCH" | "UNASSIGNED" | "COMMAND_RECORDED" | "NOT_ELIGIBLE" | "LANE_BLOCKED";
                    laneId: string | null;
                } & {
                    [key: string]: unknown;
                })[];
            } & {
                [key: string]: unknown;
            };
            executionProposal: {
                /** @constant */
                ruleVersion: 1;
                /** Format: uuid */
                selectedMovementId: string | null;
                selectedLaneId: string | null;
                ranking: ({
                    /** Format: uuid */
                    movementId: string;
                    /** @enum {unknown} */
                    reason: "READY" | "WORLD_MISMATCH" | "OBSERVATION_STALE" | "ROUTE_UNCERTAIN" | "ZONE_MISMATCH" | "OWNER_MISMATCH" | "UNASSIGNED" | "COMMAND_RECORDED" | "NOT_ELIGIBLE" | "LANE_BLOCKED";
                    laneId: string | null;
                } & {
                    [key: string]: unknown;
                })[];
            } & {
                [key: string]: unknown;
            };
            matches: boolean;
            /** Format: date-time */
            comparedAt: string;
        } & {
            [key: string]: unknown;
        };
        "zone-route-list": components["schemas"]["zone-route-list.v1"];
        "migration-view": components["schemas"]["migration-view.v1"];
        "migration-list": components["schemas"]["migration-list.v1"];
        "execution-task-list": components["schemas"]["execution-task-list.v1"];
        "receipt-view": {
            /** Format: uuid */
            id: string;
            siteId: string;
            externalReceiptRef: string;
            /** @enum {string} */
            state: "REGISTERED" | "SORTING" | "COMPLETED" | "RECONCILIATION_REQUIRED";
            version: number;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            completedAt: string | null;
            /** Format: date-time */
            observedAt: string;
            counts: ({
                /** @enum {string} */
                classification: "REUSABLE" | "NEEDS_CLEANING" | "DAMAGED";
                received: number;
                sorted: number;
            } & {
                [key: string]: unknown;
            })[];
            movements: ({
                /** Format: uuid */
                movementId: string;
                /** @enum {string} */
                classification: "REUSABLE" | "NEEDS_CLEANING" | "DAMAGED";
                state: string;
                movement: {
                    /** @constant */
                    zoneId: "returns";
                    quantity: number;
                    /** @enum {string} */
                    destination: "reusable" | "cleaning" | "damaged";
                    source: string;
                } & {
                    [key: string]: unknown;
                };
                /** Format: uuid */
                taskId: string | null;
                taskState: string | null;
                taskVersion: number | null;
                transportFailures: number | null;
                transportPaused: boolean | null;
                lastError: string | null;
            } & {
                [key: string]: unknown;
            })[];
        } & {
            [key: string]: unknown;
        };
        "receipt-page": {
            items: ({
                /** Format: uuid */
                id: string;
                siteId: string;
                externalReceiptRef: string;
                /** @enum {string} */
                state: "REGISTERED" | "SORTING" | "COMPLETED" | "RECONCILIATION_REQUIRED";
                version: number;
                /** Format: date-time */
                createdAt: string;
                /** Format: date-time */
                completedAt: string | null;
                /** Format: date-time */
                observedAt: string;
                counts: ({
                    /** @enum {string} */
                    classification: "REUSABLE" | "NEEDS_CLEANING" | "DAMAGED";
                    received: number;
                    sorted: number;
                } & {
                    [key: string]: unknown;
                })[];
                movements: ({
                    /** Format: uuid */
                    movementId: string;
                    /** @enum {string} */
                    classification: "REUSABLE" | "NEEDS_CLEANING" | "DAMAGED";
                    state: string;
                    movement: {
                        /** @constant */
                        zoneId: "returns";
                        quantity: number;
                        /** @enum {string} */
                        destination: "reusable" | "cleaning" | "damaged";
                        source: string;
                    } & {
                        [key: string]: unknown;
                    };
                    /** Format: uuid */
                    taskId: string | null;
                    taskState: string | null;
                    taskVersion: number | null;
                    transportFailures: number | null;
                    transportPaused: boolean | null;
                    lastError: string | null;
                } & {
                    [key: string]: unknown;
                })[];
            } & {
                [key: string]: unknown;
            })[];
            /** Format: uuid */
            nextCursor: string | null;
            /** Format: date-time */
            observedAt: string;
        } & {
            [key: string]: unknown;
        };
        "return-counters": {
            siteId: string;
            /** Format: date-time */
            observedAt: string;
            classifications: ({
                /** @enum {string} */
                classification: "REUSABLE" | "NEEDS_CLEANING" | "DAMAGED";
                received: number;
                sorted: number;
                outstanding: number;
            } & {
                [key: string]: unknown;
            })[];
        } & {
            [key: string]: unknown;
        };
        "return-task-list": ({
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            movementId: string;
            /** Format: uuid */
            allocationId: string;
            /** Format: uuid */
            receiptId: string;
            /** @enum {string} */
            classification: "REUSABLE" | "NEEDS_CLEANING" | "DAMAGED";
            /** @constant */
            zoneId: "returns";
            /** @enum {string} */
            state: "READY" | "BLOCKED" | "DISPATCH_REQUESTED" | "IN_PROGRESS" | "COMPLETED" | "RECONCILIATION_REQUIRED";
            /** @constant */
            owner: "returns-service";
            epoch: number;
            version: number;
            lastError: string | null;
            transportFailures: number;
            transportPaused: boolean;
            /** Format: date-time */
            dispatchAcceptedAt: string | null;
        } & {
            [key: string]: unknown;
        })[];
        "order-page.v1": {
            items: ({
                /** Format: uuid */
                id: string;
                siteId: string;
                externalOrderRef: string;
                storeId: string;
                priority: number;
                /** @enum {string} */
                state: "ACCEPTED" | "RESERVED" | "IN_PROGRESS" | "COMPLETED" | "COMPLETED_WITH_SHORTAGE" | "SHORTAGE" | "CANCELLED";
                version: number;
                /** Format: date-time */
                createdAt: string;
                /** Format: date-time */
                completedAt: string | null;
                /** Format: date-time */
                observedAt: string;
                lines: ({
                    sku: string;
                    requested: number;
                    reserved: number;
                    shortage: number;
                } & {
                    [key: string]: unknown;
                })[];
                movements: ({
                    /** Format: uuid */
                    movementId: string;
                    state: string;
                    movement: {
                        zoneId: string;
                        quantity: number;
                        destination: string;
                        source: string;
                    } & {
                        [key: string]: unknown;
                    };
                } & {
                    [key: string]: unknown;
                })[];
            } & {
                [key: string]: unknown;
            })[];
            /** Format: uuid */
            nextCursor: string | null;
            /** Format: date-time */
            observedAt: string;
        } & {
            [key: string]: unknown;
        };
        "order-request.v1": {
            sourceSystem: string;
            externalOrderRef: string;
            storeId: string;
            priority: number;
            lines: {
                sku: string;
                quantity: number;
            }[];
        };
        "order-view.v1": {
            /** Format: uuid */
            id: string;
            siteId: string;
            externalOrderRef: string;
            storeId: string;
            priority: number;
            /** @enum {string} */
            state: "ACCEPTED" | "RESERVED" | "IN_PROGRESS" | "COMPLETED" | "COMPLETED_WITH_SHORTAGE" | "SHORTAGE" | "CANCELLED";
            version: number;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            completedAt: string | null;
            /** Format: date-time */
            observedAt: string;
            lines: ({
                sku: string;
                requested: number;
                reserved: number;
                shortage: number;
            } & {
                [key: string]: unknown;
            })[];
            movements: ({
                /** Format: uuid */
                movementId: string;
                state: string;
                movement: {
                    zoneId: string;
                    quantity: number;
                    destination: string;
                    source: string;
                } & {
                    [key: string]: unknown;
                };
            } & {
                [key: string]: unknown;
            })[];
            cancellation?: ({
                /** Format: uuid */
                cancellationId: string;
                /** @enum {string} */
                state: "PENDING" | "PAUSED" | "CANCELLED" | "DENIED";
                version: number;
                attempts: number;
                lastError: string | null;
                /** Format: date-time */
                createdAt: string;
                /** Format: date-time */
                finishedAt: string | null;
            } & {
                [key: string]: unknown;
            }) | null;
        } & {
            [key: string]: unknown;
        };
        "receipt-request.v1": {
            sourceSystem: string;
            externalReceiptRef: string;
            counts: {
                REUSABLE: number;
                NEEDS_CLEANING: number;
                DAMAGED: number;
            };
        };
        "task-list.v1": ({
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            movementId: string;
            /** Format: uuid */
            orderId: string;
            zoneId: string;
            state: string;
            owner: string;
            epoch: number | null;
            version: number;
            lastError: string | null;
            /** Format: date-time */
            eligibleAt: string;
        } & {
            [key: string]: unknown;
        })[];
        "equipment-view.v1": {
            /** Format: uuid */
            worldId?: string;
            /** Format: uuid */
            journalGeneration?: string;
            completeHistory?: boolean;
            /** Format: date-time */
            observedAt?: string;
            journalHighWater?: number;
            stale: boolean;
            worldMismatch?: boolean;
            lanes: ({
                siteId: string;
                laneId: string;
                zoneId: string;
                blocked: boolean;
                version: number;
            } & {
                [key: string]: unknown;
            })[];
        } & {
            [key: string]: unknown;
        };
        "command-view.v1": {
            /** Format: uuid */
            commandId: string;
            /** Format: uuid */
            allocationId: string;
            /** Format: uuid */
            movementId: string;
            siteId: string;
            owner: string;
            epoch: number;
            state: string;
            version: number;
            attempts: number;
            failureAttempts: number;
            payload: {
                quantity: number;
                source: string;
                destination: string;
                zoneId: string;
                laneId: string;
                /** Format: uuid */
                worldId: string;
            } & {
                [key: string]: unknown;
            };
            evidence: ({
                /** Format: uuid */
                worldId: string;
                executionSequence: number | null;
                state: string;
                /** Format: date-time */
                completedAt: string | null;
            } & {
                [key: string]: unknown;
            }) | null;
            lastError: string | null;
            evidenceVersion?: number;
            acceptedEver?: boolean;
            lastObservation?: {
                [key: string]: unknown;
            } | null;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            completedAt: string | null;
        } & {
            [key: string]: unknown;
        };
        "reconciliation-request.v1": {
            reason: string;
            expectedVersion: number;
        };
        "cancellation-certificate.v1": {
            /** Format: uuid */
            cancellationId: string;
            /** Format: uuid */
            orderId: string;
            siteId: string;
            /** @constant */
            state: "FENCED";
            /** Format: uuid */
            worldId: string;
            /** Format: uuid */
            journalGeneration: string;
            proofHash: string;
            /** Format: date-time */
            issuedAt: string;
            movements: ({
                /** Format: uuid */
                movementId: string;
                /** Format: uuid */
                allocationId: string;
                allocationVersion: number;
                /** @constant */
                proof: "NEVER_SUBMITTED";
            } & {
                [key: string]: unknown;
            })[];
        } & {
            [key: string]: unknown;
        };
        "execution-task-list.v1": ({
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            movementId: string;
            /** Format: uuid */
            allocationId: string;
            zoneId: string;
            state: string;
            owner: string;
            epoch: number;
            version: number;
            lastError: string | null;
            /** Format: date-time */
            eligibleAt: string;
            transportFailures: number;
            transportPaused: boolean;
            /** Format: date-time */
            dispatchAcceptedAt: string | null;
        } & {
            [key: string]: unknown;
        })[];
        "zone-route-list.v1": ({
            siteId: string;
            zoneId: string;
            owner: string;
            epoch: number;
            state: string;
            version: number;
        } & {
            [key: string]: unknown;
        })[];
        "migration-list.v1": {
            items: ({
                /** Format: uuid */
                sessionId: string;
                siteId: string;
                zoneId: string;
                /** @enum {string} */
                sourceOwner: "legacy-core" | "execution-service";
                /** @enum {string} */
                targetOwner: "legacy-core" | "execution-service";
                sourceEpoch: number;
                targetEpoch: number | null;
                /** @enum {string} */
                phase: "DRAINING" | "RECONCILING" | "READY_TO_SWITCH" | "OBSERVING" | "COMPLETED" | "REVERSING" | "REVERSED" | "SUPERSEDED" | "CANCELLED";
                version: number;
                actor: string;
                reason: string;
                /** Format: uuid */
                reversesSessionId: string | null;
                inventoryCount: number;
                verifiedCount: number;
                inventoryHash: string | null;
                checkpointHash: string | null;
                checkpoint: ({
                    inventoryHash: string;
                    count: number;
                    /** Format: uuid */
                    worldId: string;
                    /** Format: uuid */
                    journalGeneration: string;
                    journalHighWater: number;
                    proofChunkHashes: string[];
                } & {
                    [key: string]: unknown;
                }) | null;
                blockers: {
                    count: number;
                    items: ({
                        reason: string;
                        /** Format: uuid */
                        movementId?: string;
                        /** Format: uuid */
                        allocationId?: string;
                        owner?: string;
                        allocationState?: string;
                        commandState?: string;
                    } & {
                        [key: string]: unknown;
                    })[];
                } & {
                    [key: string]: unknown;
                };
                observation: ({
                    sampleCount: number;
                    dispatchP99Millis: number;
                    withinTwoSeconds: boolean;
                    proofHash: string;
                    movements: ({
                        /** Format: uuid */
                        movementId: string;
                        dispatchMillis: number;
                    } & {
                        [key: string]: unknown;
                    })[];
                    proof: {
                        [key: string]: unknown;
                    }[];
                } & {
                    [key: string]: unknown;
                }) | null;
                transportAttempts: number;
                transportPaused: boolean;
                lastError: string | null;
                /** Format: date-time */
                createdAt: string;
                /** Format: date-time */
                switchedAt: string | null;
                /** Format: date-time */
                finishedAt: string | null;
                /** Format: date-time */
                observedAt: string;
            } & {
                [key: string]: unknown;
            })[];
            /** Format: date-time */
            observedAt: string;
        } & {
            [key: string]: unknown;
        };
        "migration-view.v1": {
            /** Format: uuid */
            sessionId: string;
            siteId: string;
            zoneId: string;
            /** @enum {string} */
            sourceOwner: "legacy-core" | "execution-service";
            /** @enum {string} */
            targetOwner: "legacy-core" | "execution-service";
            sourceEpoch: number;
            targetEpoch: number | null;
            /** @enum {string} */
            phase: "DRAINING" | "RECONCILING" | "READY_TO_SWITCH" | "OBSERVING" | "COMPLETED" | "REVERSING" | "REVERSED" | "SUPERSEDED" | "CANCELLED";
            version: number;
            actor: string;
            reason: string;
            /** Format: uuid */
            reversesSessionId: string | null;
            inventoryCount: number;
            verifiedCount: number;
            inventoryHash: string | null;
            checkpointHash: string | null;
            checkpoint: ({
                inventoryHash: string;
                count: number;
                /** Format: uuid */
                worldId: string;
                /** Format: uuid */
                journalGeneration: string;
                journalHighWater: number;
                proofChunkHashes: string[];
            } & {
                [key: string]: unknown;
            }) | null;
            blockers: {
                count: number;
                items: ({
                    reason: string;
                    /** Format: uuid */
                    movementId?: string;
                    /** Format: uuid */
                    allocationId?: string;
                    owner?: string;
                    allocationState?: string;
                    commandState?: string;
                } & {
                    [key: string]: unknown;
                })[];
            } & {
                [key: string]: unknown;
            };
            observation: ({
                sampleCount: number;
                dispatchP99Millis: number;
                withinTwoSeconds: boolean;
                proofHash: string;
                movements: ({
                    /** Format: uuid */
                    movementId: string;
                    dispatchMillis: number;
                } & {
                    [key: string]: unknown;
                })[];
                proof: {
                    [key: string]: unknown;
                }[];
            } & {
                [key: string]: unknown;
            }) | null;
            transportAttempts: number;
            transportPaused: boolean;
            lastError: string | null;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            switchedAt: string | null;
            /** Format: date-time */
            finishedAt: string | null;
            /** Format: date-time */
            observedAt: string;
        } & {
            [key: string]: unknown;
        };
        "migration-request.v1": {
            reason: string;
            expectedVersion: number;
            /** @enum {unknown} */
            targetOwner: "legacy-core" | "execution-service";
        };
        "equipment-command.v1": {
            /** Format: uuid */
            commandId: string;
            /** Format: uuid */
            allocationId: string;
            /** Format: uuid */
            movementId: string;
            siteId: string;
            /** Format: uuid */
            loadId: string;
            /** Format: uuid */
            worldId: string;
            /** Format: uuid */
            journalGeneration: string;
            expectedLoadVersion: number;
            source: string;
            destination: string;
            /** @enum {string} */
            zoneId: "ambient" | "chilled" | "returns";
            laneId: string;
            quantity: number;
        };
        "event-envelope.v1": {
            /** Format: uuid */
            eventId: string;
            eventType: string;
            /** @constant */
            schemaVersion: 1;
            /** Format: date-time */
            occurredAt: string;
            siteId: string;
            source: string;
            aggregateType: string;
            /** Format: uuid */
            aggregateId: string;
            aggregateVersion: number;
            /** Format: uuid */
            correlationId: string;
            /** Format: uuid */
            causationId: string | null;
            traceparent: string | null;
            payload: Record<string, never>;
        } & {
            [key: string]: unknown;
        };
        "movement.v1": {
            /** Format: uuid */
            movementId: string;
            /** Format: uuid */
            reservationId: string | null;
            siteId: string;
            /** @enum {unknown} */
            product: "fulfilment" | "returns";
            /** @enum {string} */
            zoneId: "ambient" | "chilled" | "returns";
            /** Format: uuid */
            loadId: string;
            source: string;
            /** @enum {unknown} */
            destination: "outbound-staging" | "reusable" | "cleaning" | "damaged";
            quantity: number;
            priority: number;
            /** Format: date-time */
            eligibleAt: string;
        } & {
            [key: string]: unknown;
        };
    };
    responses: {
        /** @description Bounded problem response without internal exceptions */
        problem: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem"];
            };
        };
    };
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listOrders: {
        parameters: {
            query?: {
                limit?: number;
                cursor?: string;
            };
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current owner projection, with observation time where applicable */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["order-page.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    createOrders: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["order-request.v1"];
            };
        };
        responses: {
            /** @description Committed */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["accepted"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    getOrders: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current owner projection, with observation time where applicable */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["order-view.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    listReturnReceipts: {
        parameters: {
            query?: {
                limit?: number;
                cursor?: string;
            };
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Site-scoped current returns projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["receipt-page"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    createReturnReceipts: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["receipt-request.v1"];
            };
        };
        responses: {
            /** @description Committed */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["accepted"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    getReturnReceipts: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Site-scoped current returns projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["receipt-view"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    listTasks: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current owner projection, with observation time where applicable */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["task-list.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    getEquipment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current owner projection, with observation time where applicable */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["equipment-view.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    getCommand: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current owner projection, with observation time where applicable */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["command-view.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    listRecoveryCommands: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Observed recovery queue */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["problem"];
            401: components["responses"]["problem"];
            403: components["responses"]["problem"];
            404: components["responses"]["problem"];
            409: components["responses"]["problem"];
            413: components["responses"]["problem"];
            422: components["responses"]["problem"];
            503: components["responses"]["problem"];
        };
    };
    investigateCommand: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["reconciliation-request.v1"];
            };
        };
        responses: {
            /** @description Investigation request recorded; repeats with the same key return the same response */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["problem"];
            401: components["responses"]["problem"];
            403: components["responses"]["problem"];
            404: components["responses"]["problem"];
            409: components["responses"]["problem"];
            413: components["responses"]["problem"];
            422: components["responses"]["problem"];
            503: components["responses"]["problem"];
        };
    };
    cancelOrder: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["reconciliation-request.v1"];
            };
        };
        responses: {
            /** @description Cancellation committed, with complete adapter fence certificate */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        /** Format: uuid */
                        cancellationId: string;
                        /** Format: uuid */
                        orderId: string;
                        /** @constant */
                        state: "CANCELLED";
                        version: number;
                        certificate: components["schemas"]["cancellation-certificate.v1"];
                    } & {
                        [key: string]: unknown;
                    };
                };
            };
            400: components["responses"]["problem"];
            401: components["responses"]["problem"];
            403: components["responses"]["problem"];
            404: components["responses"]["problem"];
            409: components["responses"]["problem"];
            413: components["responses"]["problem"];
            422: components["responses"]["problem"];
            503: components["responses"]["problem"];
        };
    };
    resumeCancellation: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
                id: string;
                cancellationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["reconciliation-request.v1"];
            };
        };
        responses: {
            /** @description Retry recorded, with cancellationId, orderId, state PENDING and version */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["problem"];
            401: components["responses"]["problem"];
            403: components["responses"]["problem"];
            404: components["responses"]["problem"];
            409: components["responses"]["problem"];
            413: components["responses"]["problem"];
            422: components["responses"]["problem"];
            503: components["responses"]["problem"];
        };
    };
    listShadowComparisons: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Comparison totals and retained summaries */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            default: components["responses"]["problem"];
        };
    };
    getShadowComparison: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Identical input with both proposals */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["scheduling-comparison"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    recoverLegacyTask: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["reconciliation-request.v1"];
            };
        };
        responses: {
            /** @description Audited status investigation requested */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            default: components["responses"]["problem"];
        };
    };
    listExecutionTasks: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Independent execution task owner */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["execution-task-list.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    recoverExecutionTask: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["reconciliation-request.v1"];
            };
        };
        responses: {
            /** @description Audited status investigation requested */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            default: components["responses"]["problem"];
        };
    };
    listZoneRoutes: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current migration observations */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["zone-route-list.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    listMigrations: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current migration observations */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["migration-list.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    getMigration: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current migration observations */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["migration-view.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    startMigration: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
                zoneId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["migration-request.v1"];
            };
        };
        responses: {
            /** @description Durably recorded session */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["migration-view.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    recoverMigration: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["reconciliation-request.v1"];
            };
        };
        responses: {
            /** @description Durably recorded session */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["migration-view.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    cancelMigration: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["reconciliation-request.v1"];
            };
        };
        responses: {
            /** @description Durably recorded session */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["migration-view.v1"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    getReturnCounters: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Site-scoped current returns projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["return-counters"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    getReturnTasks: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Site-scoped current returns projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["return-task-list"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
    recoverReturnTask: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                siteId: string;
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["reconciliation-request.v1"];
            };
        };
        responses: {
            /** @description Audited status investigation requested */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            default: components["responses"]["problem"];
        };
    };
}
