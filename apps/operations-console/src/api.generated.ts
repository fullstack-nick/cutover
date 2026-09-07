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
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        "equipment-command": {
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
        "event-envelope": {
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
        "migration-request": {
            reason: string;
            expectedVersion: number;
            /** @enum {unknown} */
            targetOwner: "legacy-core" | "execution-service";
        };
        movement: {
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
        "order-request": {
            sourceSystem: string;
            externalOrderRef: string;
            storeId: string;
            priority: number;
            lines: {
                sku: string;
                quantity: number;
            }[];
        };
        "receipt-request": {
            sourceSystem: string;
            externalReceiptRef: string;
            counts: {
                REUSABLE: number;
                NEEDS_CLEANING: number;
                DAMAGED: number;
            };
        };
        "reconciliation-request": {
            reason: string;
            expectedVersion: number;
        };
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
        "order-view": {
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
        };
        "order-page": {
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
        "task-list": ({
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
        "equipment-view": {
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
        "command-view": {
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
                executionSequence: number;
                state: string;
                /** Format: date-time */
                completedAt: string;
            } & {
                [key: string]: unknown;
            }) | null;
            lastError: string | null;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            completedAt: string | null;
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
                    "application/json": components["schemas"]["order-page"];
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
                "application/json": components["schemas"]["order-request"];
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
                    "application/json": components["schemas"]["order-view"];
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
            /** @description Page with items, nextCursor and observedAt */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
                "application/json": components["schemas"]["receipt-request"];
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
            /** @description Resource projection and observation time */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
                    "application/json": components["schemas"]["task-list"];
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
                    "application/json": components["schemas"]["equipment-view"];
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
                    "application/json": components["schemas"]["command-view"];
                };
            };
            default: components["responses"]["problem"];
        };
    };
}
