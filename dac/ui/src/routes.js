/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Route, IndexRoute, Redirect, IndexRedirect } from 'react-router';
import React from 'react';

import { UserIsAuthenticated, UserIsAdmin } from 'utils/authWrappers';

import Acceleration from 'dyn-load/pages/AdminPage/subpages/acceleration/Acceleration';
import Roles from 'dyn-load/pages/AdminPage/subpages/Roles';

import App from './containers/App';

import HomePage from './pages/HomePage/HomePage';
import HomeModals from './pages/HomePage/HomeModals';
import Home from './pages/HomePage/subpages/Home';
import AllSpaces from './pages/HomePage/subpages/AllSpaces/AllSpaces';
import AllSources from './pages/HomePage/subpages/AllSources/AllSources';

import ExploreModals from './pages/ExplorePage/ExploreModals';
import ExplorePage from './pages/ExplorePage/ExplorePageController';

import AccountPage from './pages/AccountPage/AccountPage';
import Info from './pages/AccountPage/subpages/InfoController';
import Datastore from './pages/AccountPage/subpages/Datastore';
import Jdbcodbc from './pages/AccountPage/subpages/Jdbcodbc';
import Api from './pages/AccountPage/subpages/Api';
import Business from './pages/AccountPage/subpages/Business';

import AuthenticationPage from './pages/AuthenticationPage/AuthenticationPage';
import SignupPage from './pages/SignupPage/SignupPage';
import ServerStatusPage from './pages/ServerStatusPage/ServerStatusPage';

import AdminPage from './pages/AdminPage/AdminPage';
import NodeActivity from './pages/AdminPage/subpages/NodeActivity';
import Users from './pages/AdminPage/subpages/Users';
import Advanced from './pages/AdminPage/subpages/Advanced';
import EmailDomain from './pages/AdminPage/subpages/EmailDomain';
import Data from './pages/AdminPage/subpages/Data';
import Logging from './pages/AdminPage/subpages/Logging';
import Audit from './pages/AdminPage/subpages/Audit';
import UsersV2 from './pages/AdminPage/subpages/UsersV2';
import Provisioning from './pages/AdminPage/subpages/Provisioning';
import Support from './pages/AdminPage/subpages/Support';


import AdminModals from './pages/AdminPage/AdminModals';
import AccountModals from './pages/AccountPage/AccountModals';


import JobPage from './pages/JobPage/JobPage';
import JobModals from './pages/JobPage/JobModals';

import Page from './components/Page';

window.React = React;

export const SIGNUP_PATH = '/signup';

export const LOGIN_PATH = '/login';
export function getLoginUrl() {
  return `${LOGIN_PATH}?redirect=${encodeURIComponent(window.location.href.slice(window.location.origin.length))}`;
}

export default (
  <Route path='/' component={App}>
    {/* TODO conflict with (/:resources), need to change resources for all components */}
    <Redirect from='/home' to='/'/>
    <Redirect from='/*/**/' to='/*/**'/>
    <Route component={UserIsAuthenticated(JobModals)}>
      <Route component={Page}>
        <Route path='/jobs(/:queryId)' component={JobPage} />
      </Route>
    </Route>
    <Route component={UserIsAuthenticated(AccountModals)}>
      <Route component={Page}>
        <Route path='/account' component={AccountPage} >
          <IndexRedirect to='/account/info' />
          <Route path='/account/info' component={Info} />
          <Route path='/account/datastore' component={Datastore} />
          <Route path='/account/jdbcodbc' component={Jdbcodbc} />
          <Route path='/account/api' component={Api} />
          <Route path='/account/business' component={Business} />
        </Route>
      </Route>
    </Route>
    <Route component={UserIsAdmin(AdminModals)}>
      <Route component={Page}>
        <Route path='/admin' component={AdminPage} >
          <IndexRedirect to='/admin/nodeActivity' />
          <Route path='/admin/acceleration' component={Acceleration} />
          <Route path='/admin/nodeActivity' component={NodeActivity} />
          <Route path='/admin/users' component={UserIsAdmin(Users)} />
          <Route path='/admin/roles' component={Roles} />
          <Route path='/admin/advanced' component={Advanced} />
          <Route path='/admin/emailDomain' component={EmailDomain} />
          <Route path='/admin/data' component={Data} />
          <Route path='/admin/logging' component={Logging} />
          <Route path='/admin/audit' component={Audit} />
          <Route path='/admin/usersv2' component={UsersV2} />
          <Route path='/admin/provisioning' component={Provisioning} />
          <Route path='/admin/support' component={Support} />
        </Route>
      </Route>
    </Route>
    <Route component={Page}>
      <Route path='/spaces/recent' component={HomePage} />

      <Route path={LOGIN_PATH} component={AuthenticationPage} />
      <Route path={SIGNUP_PATH} component={SignupPage} />
      <Route path='/status' component={ServerStatusPage} />
    </Route>
    <Route component={UserIsAuthenticated(HomeModals)}>
      <Route component={Page}>
        <IndexRoute component={Home} /> {/* todo: is this valid?*/}
        <Route path='/source(/:resourceId)' component={Home} />
        <Route path='/space/:spaceId' component={Home} />
        <Route path='/:rootType/:rootName/folder/**' component={Home} />
        <Route path='/spaces/list' component={AllSpaces} />
        <Route path='/sources/list' component={AllSources} />
      </Route>
    </Route>
    <Route component={UserIsAuthenticated(ExploreModals)}>
      <Route component={Page}>
        <Route path='/new_query' component={ExplorePage} />
        <Route path='/:resources(/:resourceId)/:tableId(/:pageType)' component={ExplorePage} />
      </Route>
    </Route>
  </Route>
);
