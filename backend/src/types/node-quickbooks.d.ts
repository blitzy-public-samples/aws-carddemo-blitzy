/**
 * Type definitions for node-quickbooks
 * 
 * Provides basic type declarations for the node-quickbooks library.
 * This file provides minimal types needed for compilation.
 */

declare module 'node-quickbooks' {
  class QuickBooks {
    constructor(
      consumerKey: string,
      consumerSecret: string,
      token: string,
      tokenSecret: string | boolean | null,
      realmId: string,
      useSandbox: boolean,
      debug: boolean,
      minorversion?: number | null,
      oauthversion?: string,
      refreshToken?: string | null
    );

    setTokenRefreshCallback(callback: (accessToken: string, refreshToken: string) => void | Promise<void>): void;

    createInvoice(invoice: any, callback: (err: any, data: any) => void): void;
    createVendor(vendor: any, callback: (err: any, data: any) => void): void;
    createBill(bill: any, callback: (err: any, data: any) => void): void;
    createPurchase(purchase: any, callback: (err: any, data: any) => void): void;
    getCompanyInfo(realmId: string, callback: (err: any, data: any) => void): void;
    findAccounts(criteria: any, callback: (err: any, data: any) => void): void;
    reportQuery(query: string, callback: (err: any, data: any) => void): void;

    static authorizeUrl(
      clientId: string,
      redirectUri: string,
      state: string
    ): string;

    static getToken(
      redirectUri: string,
      authorizationCode: string,
      clientId: string,
      clientSecret: string,
      callback: (err: any, data: any) => void
    ): void;

    static refreshToken(
      refreshToken: string,
      clientId: string,
      clientSecret: string,
      callback: (err: any, data: any) => void
    ): void;

    static revokeToken(
      token: string,
      useRefresh: boolean,
      clientId: string,
      clientSecret: string,
      callback: (err: any, data: any) => void
    ): void;
  }

  export = QuickBooks;
}
