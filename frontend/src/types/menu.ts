/**
 * Navigation-menu DTOs.
 * Mirrors `backend/app/schemas/menu.py`.
 * References: app/cpy/COMEN02Y.cpy (regular menu, 10 options),
 * app/cpy/COADM02Y.cpy (admin menu, 4 options); screens COMEN01 / COADM01.
 */

/** A single selectable menu entry. option_number is an integer (9(02)). */
export interface MenuOption {
    option_number: number;
    option_name: string;
    program_name: string;
    user_type?: 'A' | 'U';
}

/** Menu payload returned per role. */
export interface MenuResponse {
    menu_options: MenuOption[];
    menu_title?: string;
    user_type?: string;
}
