import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'Home',
      component: () => import('@/views/HomeView.vue')
    },
    {
      path: '/search',
      name: 'Search',
      component: () => import('@/views/SearchView.vue')
    },
    {
      path: '/image/:id',
      name: 'ImageDetail',
      component: () => import('@/views/ImageDetailView.vue')
    },
    {
      path: '/tags',
      name: 'TagManager',
      component: () => import('@/views/TagManagerView.vue')
    }
  ]
})

export default router
