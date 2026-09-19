export type View = 'events' | 'reservations' | 'admin' | 'terms' | 'privacy'

const paths: Record<View, string> = {
  events: '/', reservations: '/reservations', admin: '/admin', terms: '/terms', privacy: '/privacy',
}

export function viewFromPath(pathname: string): View {
  return (Object.keys(paths) as View[]).find((view) => paths[view] === pathname) ?? 'events'
}

export const pathForView = (view: View) => paths[view]
