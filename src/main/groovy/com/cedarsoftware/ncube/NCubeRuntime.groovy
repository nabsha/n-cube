package com.cedarsoftware.ncube

import com.cedarsoftware.ncube.util.CdnClassLoader
import com.cedarsoftware.ncube.util.GCacheManager
import com.cedarsoftware.util.CallableBean
import com.cedarsoftware.util.CaseInsensitiveSet
import com.cedarsoftware.util.IOUtilities
import com.cedarsoftware.util.StringUtilities
import com.cedarsoftware.util.SystemUtilities
import com.cedarsoftware.util.TrackingMap
import com.cedarsoftware.util.io.JsonObject
import com.cedarsoftware.util.io.JsonReader
import com.cedarsoftware.util.io.JsonWriter
import groovy.transform.CompileStatic
import ncube.grv.method.NCubeGroovyController
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.regex.Pattern

import static com.cedarsoftware.ncube.NCubeConstants.CLASSPATH_CUBE
import static com.cedarsoftware.ncube.NCubeConstants.CONTROLLER_BEAN
import static com.cedarsoftware.ncube.NCubeConstants.MANAGER_BEAN
import static com.cedarsoftware.ncube.NCubeConstants.NCUBE_PARAMS
import static com.cedarsoftware.ncube.NCubeConstants.NCUBE_PARAMS_BRANCH
import static com.cedarsoftware.ncube.NCubeConstants.PROPERTY_CACHE
import static com.cedarsoftware.ncube.NCubeConstants.SYS_BOOTSTRAP

/**
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br>
 *         Copyright (c) Cedar Software LLC
 *         <br><br>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br><br>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br><br>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */

@CompileStatic
class NCubeRuntime implements NCubeMutableClient, NCubeRuntimeClient, NCubeTestClient
{
    private static final String MUTABLE_ERROR = 'Non-runtime method called:'
    private final CacheManager ncubeCacheManager
    private final CacheManager adviceCacheManager
    private final ConcurrentMap<ApplicationID, GroovyClassLoader> localClassLoaders = new ConcurrentHashMap<>()
    private static final Logger LOG = LoggerFactory.getLogger(NCubeRuntime.class)
    // not private in case we want to tweak things for testing.
    protected volatile ConcurrentMap<String, Object> systemParams = null
    protected final CallableBean bean
    private final boolean allowMutableMethods
    private final String beanName

    NCubeRuntime(CallableBean bean, CacheManager ncubeCacheManager, boolean allowMutableMethods)
    {
        this.bean = bean
        this.ncubeCacheManager = ncubeCacheManager
        this.adviceCacheManager = new GCacheManager()
        this.allowMutableMethods = allowMutableMethods
        this.beanName = NCubeAppContext.containsBean(MANAGER_BEAN) ? MANAGER_BEAN : CONTROLLER_BEAN
    }

    /**
     *
     * Fetch an array of NCubeInfoDto's where the cube names match the cubeNamePattern (contains) and
     * the content (in JSON format) 'contains' the passed in content String.
     * @param appId ApplicationID on which we are working
     * @param cubeNamePattern cubeNamePattern String pattern to match cube names
     * @param content String value that is 'contained' within the cube's JSON
     * @param options map with possible keys:
     *                changedRecordsOnly - default false ->  Only searches changed records if true.
     *                activeRecordsOnly - default false -> Only searches non-deleted records if true.
     *                deletedRecordsOnly - default false -> Only searches deleted records if true.
     *                cacheResult - default false -> Cache the cubes that match this result..
     * @return List<NCubeInfoDto>
     */
    List<NCubeInfoDto> search(ApplicationID appId, String cubeNamePattern, String content, Map options)
    {
        Object[] searchResults = bean.call(beanName, 'search', [appId, cubeNamePattern, content, options]) as Object[]
        List<NCubeInfoDto> dtos = []
        searchResults.each { NCubeInfoDto dto -> dtos.add(dto) }
        return dtos
    }

    /**
     * Fetch an n-cube by name from the given ApplicationID.  If no n-cubes
     * are loaded, then a loadCubes() call is performed and then the
     * internal cache is checked again.  If the cube is not found, null is
     * returned.
     */
    NCube getCube(ApplicationID appId, String cubeName)
    {
        if (appId == null)
        {
            throw new IllegalArgumentException('ApplicationID cannot be null')
        }
        if (StringUtilities.isEmpty(cubeName))
        {
            throw new IllegalArgumentException('cubeName cannot be null')
        }
        return getCubeInternal(appId, cubeName)
    }

    Object[] getTests(ApplicationID appId, String cubeName)
    {
        Object[] result = bean.call(beanName, 'getTests', [appId, cubeName]) as Object[]
        return result
    }

    String getUserId()
    {
        String userId = bean.call(beanName, 'getUserId', []) as String
        return userId
    }

    Boolean updateCube(NCube ncube)
    {
        verifyAllowMutable('updateCube')
        Boolean result = bean.call(beanName, 'updateCube', [ncube]) as Boolean

        if (CLASSPATH_CUBE.equalsIgnoreCase(ncube.name))
        {   // If the sys.classpath cube is changed, then the entire class loader must be dropped.  It will be lazily rebuilt.
            clearCache(ncube.applicationID)
        }
        clearCubeFromCache(ncube.applicationID, ncube.name)
        return result
    }

    NCube loadCubeById(long id)
    {
        NCube ncube = bean.call(beanName, 'loadCubeById', [id]) as NCube
        prepareCube(ncube)
        return ncube
    }

    void createCube(NCube ncube)
    {
        verifyAllowMutable('createCube')
        bean.call(beanName, 'createCube', [ncube])
        prepareCube(ncube)
    }

    Boolean duplicate(ApplicationID oldAppId, ApplicationID newAppId, String oldName, String newName)
    {
        verifyAllowMutable('duplicate')
        Boolean result = bean.call(beanName, 'duplicate', [oldAppId, newAppId, oldName, newName]) as Boolean
        clearCubeFromCache(newAppId, newName)
        return result
    }

    Boolean assertPermissions(ApplicationID appId, String resource, Action action)
    {
        Boolean result = bean.call(beanName, 'assertPermissions', [appId, resource, action]) as Boolean
        return result
    }

    Boolean checkPermissions(ApplicationID appId, String resource, String action)
    {
        Boolean result = bean.call(beanName, 'checkPermissions', [appId, resource, action]) as Boolean
        return result
    }

    Boolean isAppAdmin(ApplicationID appId)
    {
        Boolean result = bean.call(beanName, 'isAppAdmin', [appId]) as Boolean
        return result
    }

    String getAppLockedBy(ApplicationID appId)
    {
        String result = bean.call(beanName, 'getAppLockedBy', [appId]) as String
        return result
    }

    Boolean lockApp(ApplicationID appId, boolean shouldLock)
    {
        verifyAllowMutable('lockApp')
        Boolean result = bean.call(beanName, 'lockApp', [appId, shouldLock]) as Boolean
        return result
    }

    Integer moveBranch(ApplicationID appId, String newSnapVer)
    {
        verifyAllowMutable('moveBranch')
        Integer result = bean.call(beanName, 'moveBranch', [appId, newSnapVer]) as Integer
        clearCache(appId)
        return result
    }

    Integer releaseVersion(ApplicationID appId, String newSnapVer)
    {
        verifyAllowMutable('releaseVersion')
        Integer result = bean.call(beanName, 'releaseVersion', [appId, newSnapVer]) as Integer
        clearCache()
        return result
    }

    Integer releaseCubes(ApplicationID appId, String newSnapVer)
    {
        verifyAllowMutable('releaseCubes')
        Integer result = bean.call(beanName, 'releaseCubes', [appId, newSnapVer]) as Integer
        clearCache()
        return result
    }

    Boolean restoreCubes(ApplicationID appId, Object[] cubeNames)
    {
        verifyAllowMutable('restoreCubes')
        Boolean result = bean.call(beanName, 'restoreCubes', [appId, cubeNames]) as Boolean
        return result
    }

    List<NCubeInfoDto> getRevisionHistory(ApplicationID appId, String cubeName, boolean ignoreVersion = false)
    {
        List<NCubeInfoDto> result = bean.call(beanName, 'getRevisionHistory', [appId, cubeName, ignoreVersion]) as List
        return result
    }

    List<String> getAppNames()
    {
        List<String> result = bean.call(beanName, 'getAppNames', []) as List
        return result
    }

    Object[] getVersions(String app)
    {
        Object[] result = bean.call(beanName, 'getVersions', [app]) as Object[]
        return result
    }

    Integer copyBranch(ApplicationID srcAppId, ApplicationID targetAppId, boolean copyWithHistory = false)
    {
        verifyAllowMutable('copyBranch')
        Integer result = bean.call(beanName, 'copyBranch', [srcAppId, targetAppId, copyWithHistory]) as Integer
        clearCache(targetAppId)
        return result
    }

    Set<String> getBranches(ApplicationID appId)
    {
        Set<String> result = bean.call(beanName, 'getBranches', [appId]) as Set
        return result
    }

    Integer getBranchCount(ApplicationID appId)
    {
        Integer result = bean.call(beanName, 'getBranchCount', [appId]) as Integer
        return result
    }

    Boolean deleteBranch(ApplicationID appId)
    {
        verifyAllowMutable('deleteBranch')
        Boolean result = bean.call(beanName, 'deleteBranch', [appId]) as Boolean
        clearCache(appId)
        return result
    }

    NCube mergeDeltas(ApplicationID appId, String cubeName, List<Delta> deltas)
    {
        verifyAllowMutable('mergeDeltas')
        NCube ncube = bean.call(beanName, 'mergeDeltas', [appId, cubeName, deltas]) as NCube
        cacheCube(ncube)
        return ncube
    }

    Boolean deleteCubes(ApplicationID appId, Object[] cubeNames)
    {
        verifyAllowMutable('deleteCubes')
        Boolean result = bean.call(beanName, 'deleteCubes', [appId, cubeNames]) as Boolean
        clearCache(appId)
        return result
    }

    void changeVersionValue(ApplicationID appId, String newVersion)
    {
        verifyAllowMutable('changeVersionValue')
        bean.call(beanName, 'changeVersionValue', [appId, newVersion])
        clearCache(appId)
        clearCache(appId.asVersion(newVersion))
    }

    Boolean renameCube(ApplicationID appId, String oldName, String newName)
    {
        verifyAllowMutable('renameCube')
        Boolean result = bean.call(beanName, 'renameCube', [appId, oldName, newName]) as Boolean
        clearCubeFromCache(appId, oldName)
        clearCubeFromCache(appId, newName)
        return result
    }

    Set<String> getReferencesFrom(ApplicationID appId, String cubeName)
    {
        Object[] results = bean.call(beanName, 'getReferencesFrom', [appId, cubeName]) as Object[]
        Set<String> refs = new CaseInsensitiveSet()
        results.each { String result -> refs.add(result) }
        return refs
    }

    List<AxisRef> getReferenceAxes(ApplicationID appId)
    {
        List<AxisRef> result = bean.call(beanName, 'getReferenceAxes', [appId]) as List
        return result
    }

    void updateReferenceAxes(Object[] axisRefs)
    {
        verifyAllowMutable('updateReferenceAxes')
        bean.call(beanName, 'updateReferenceAxes', [axisRefs])
        clearCache()
    }

    ApplicationID getApplicationID(String tenant, String app, Map<String, Object> coord)
    {
        ApplicationID.validateTenant(tenant)
        ApplicationID.validateApp(tenant)

        if (coord == null)
        {
            coord = [:]
        }

        NCube bootCube = getCube(getBootVersion(tenant, app), SYS_BOOTSTRAP)

        if (bootCube == null)
        {
            throw new IllegalStateException("Missing ${SYS_BOOTSTRAP} cube in the 0.0.0 version for the app: ${app}")
        }

        Map copy = new HashMap(coord)
        ApplicationID bootAppId = (ApplicationID) bootCube.getCell(copy, [:])
        String version = bootAppId.version
        String status = bootAppId.status
        String branch = bootAppId.branch

        if (!tenant.equalsIgnoreCase(bootAppId.tenant))
        {
            LOG.warn("sys.bootstrap cube for tenant: ${tenant}, app: ${app} is returning a different tenant: ${bootAppId.tenant} than requested. Using ${tenant} instead.")
        }

        if (!app.equalsIgnoreCase(bootAppId.app))
        {
            LOG.warn("sys.bootstrap cube for tenant: ${tenant}, app: ${app} is returning a different app: ${bootAppId.app} than requested. Using ${app} instead.")
        }

        return new ApplicationID(tenant, app, version, status, branch)
    }

    void updateAxisMetaProperties(ApplicationID appId, String cubeName, String axisName, Map<String, Object> newMetaProperties)
    {
        verifyAllowMutable('updateAxisMetaProperties')
        bean.call(beanName, 'updateAxisMetaProperties', [appId, cubeName, axisName, newMetaProperties])
        clearCubeFromCache(appId, cubeName)
    }

    Boolean saveTests(ApplicationID appId, String cubeName, Object[] tests)
    {
        verifyAllowMutable('saveTests')
        Boolean result = bean.call(beanName, 'saveTests', [appId, cubeName, tests]) as Boolean
        return result
    }

    Boolean updateNotes(ApplicationID appId, String cubeName, String notes)
    {
        verifyAllowMutable('updateNotes')
        Boolean result = bean.call(beanName, 'updateNotes', [appId, cubeName, notes]) as Boolean
        return result
    }

    String getNotes(ApplicationID appId, String cubeName)
    {
        String result = bean.call(beanName, 'getNotes', [appId, cubeName]) as String
        return result
    }

    void clearTestDatabase()
    {
        verifyAllowMutable('clearTestDatabase')
        bean.call(beanName, 'clearTestDatabase', []) as Boolean
    }

    List<NCubeInfoDto> getHeadChangesForBranch(ApplicationID appId)
    {
        List<NCubeInfoDto> changes = bean.call(beanName, 'getHeadChangesForBranch', [appId]) as List<NCubeInfoDto>
        return changes
    }

    List<NCubeInfoDto> getBranchChangesForHead(ApplicationID appId)
    {
        List<NCubeInfoDto> changes = bean.call(beanName, 'getBranchChangesForHead', [appId]) as List<NCubeInfoDto>
        return changes
    }

    List<NCubeInfoDto> getBranchChangesForMyBranch(ApplicationID appId, String branch)
    {
        List<NCubeInfoDto> changes = bean.call(beanName, 'getBranchChangesForMyBranch', [appId, branch]) as List<NCubeInfoDto>
        return changes
    }

    Map<String, Object> updateBranch(ApplicationID appId, Object[] cubeDtos = null)
    {
        verifyAllowMutable('updateBranch')
        Map<String, Object> map = bean.call(beanName, 'updateBranch', [appId, cubeDtos]) as Map<String, Object>
        clearCache(appId)
        return map
    }

    String generatePullRequestLink(ApplicationID appId, Object[] infoDtos)
    {
        verifyAllowMutable('generatePullRequestLink')
        String link = bean.call(beanName, 'generatePullRequestLink', [appId, infoDtos]) as String
        return link
    }

    Map<String, Object> mergePullRequest(String commitId)
    {
        verifyAllowMutable('mergePullRequest')
        Map result = bean.call(beanName, 'mergePullRequest', [commitId]) as Map
        return result
    }

    NCube cancelPullRequest(String commitId)
    {
        verifyAllowMutable('cancelPullRequest')
        NCube ncube = bean.call(beanName, 'cancelPullRequest', [commitId]) as NCube
        clearCubeFromCache(ncube.applicationID, ncube.name)
        return ncube
    }

    NCube reopenPullRequest(String commitId)
    {
        verifyAllowMutable('reopenPullRequest')
        NCube ncube = bean.call(beanName, 'reopenPullRequest', [commitId]) as NCube
        clearCubeFromCache(ncube.applicationID, ncube.name)
        return ncube
    }

    Object[] getPullRequests(Date startDate, Date endDate)
    {
        verifyAllowMutable('getPullRequests')
        Object[] result = bean.call(beanName, 'getPullRequests', [startDate, endDate]) as Object[]
        return result
    }

    Map<String, Object> commitBranch(ApplicationID appId, Object[] inputCubes = null)
    {
        verifyAllowMutable('commitBranch')
        Map<String, Object> map = bean.call(beanName, 'commitBranch', [appId, inputCubes]) as Map<String, Object>
        clearCache(appId)
        clearCache(appId.asHead())
        return map
    }

    Integer rollbackBranch(ApplicationID appId, Object[] names)
    {
        verifyAllowMutable('rollbackBranch')
        Integer result = bean.call(beanName, 'rollbackBranch', [appId, names]) as Integer
        clearCache(appId)
        return result
    }

    Integer acceptMine(ApplicationID appId, Object[] cubeNames)
    {
        verifyAllowMutable('acceptMine')
        Integer result = bean.call(beanName, 'acceptMine', [appId, cubeNames]) as Integer
        return result
    }

    Integer acceptTheirs(ApplicationID appId, Object[] cubeNames, String sourceBranch = ApplicationID.HEAD)
    {
        verifyAllowMutable('acceptTheirs')
        Integer result = bean.call(beanName, 'acceptTheirs', [appId, cubeNames, sourceBranch]) as Integer
        clearCache(appId)
        return result
    }

    Boolean isCubeUpToDate(ApplicationID appId, String cubeName)
    {
        Boolean result = bean.call(beanName, 'isCubeUpToDate', [appId, cubeName]) as Boolean
        return result
    }

    ApplicationID getBootVersion(String tenant, String app)
    {
        String branch = getSystemParams()[NCUBE_PARAMS_BRANCH]
        return new ApplicationID(tenant, app, "0.0.0", ReleaseStatus.SNAPSHOT.name(), StringUtilities.isEmpty(branch) ? ApplicationID.HEAD : branch)
    }

    private void verifyAllowMutable(String methodName)
    {
        if (!allowMutableMethods)
        {
            throw new IllegalStateException("${MUTABLE_ERROR} ${methodName}()")
        }
    }
    
    //-- NCube Caching -------------------------------------------------------------------------------------------------

    /**
     * Clear the cube (and other internal caches) for a given ApplicationID.
     * This will remove all the n-cubes from memory, compiled Groovy code,
     * caches related to expressions, caches related to method support,
     * advice caches, and local classes loaders (used when no sys.classpath is
     * present).
     *
     * @param appId ApplicationID for which the cache is to be cleared.
     */
    void clearCache(ApplicationID appId)
    {
        synchronized (ncubeCacheManager)
        {
            ApplicationID.validateAppId(appId)

            // Clear NCube cache
            Cache cubeCache = ncubeCacheManager.getCache(appId.cacheKey())
            cubeCache.clear()   // eviction will trigger removalListener, which clears other NCube internal caches

            GroovyBase.clearCache(appId)
            NCubeGroovyController.clearCache(appId)

            // Clear Advice cache
            Cache adviceCache = adviceCacheManager.getCache(appId.cacheKey())
            adviceCache.clear()

            // Clear ClassLoader cache
            GroovyClassLoader classLoader = localClassLoaders[appId]
            if (classLoader != null)
            {
                classLoader.clearCache()
                localClassLoaders.remove(appId)
            }
        }
    }

    void clearCubeFromCache(ApplicationID appId, String cubeName)
    {
        Cache cubeCache = ncubeCacheManager.getCache(appId.cacheKey())
        cubeCache.evict(cubeName.toLowerCase())
    }

    void clearCache()
    {
        ncubeCacheManager.cacheNames.each { String cacheKey ->
            ApplicationID appId = ApplicationID.convert(cacheKey)
            clearCache(appId)
        }
    }

    Cache getCacheForApp(ApplicationID appId)
    {
        Cache cache = ncubeCacheManager.getCache(appId.cacheKey())
        return cache
    }
    
    /**
     * Add a cube to the internal cache of available cubes.
     * @param ncube NCube to add to the list.
     */
    void addCube(NCube ncube)
    {
        ApplicationID appId = ncube.applicationID
        ApplicationID.validateAppId(appId)
        validateCube(ncube)
        prepareCube(ncube)
    }

    private void cacheCube(NCube ncube)
    {
        if (!ncube.metaProperties.containsKey(PROPERTY_CACHE) || Boolean.TRUE == ncube.getMetaProperty(PROPERTY_CACHE))
        {
            Cache cubeCache = ncubeCacheManager.getCache(ncube.applicationID.cacheKey())
            cubeCache.put(ncube.name.toLowerCase(), ncube)
        }
    }

    private NCube getCubeInternal(ApplicationID appId, String cubeName)
    {
        Cache cubeCache = ncubeCacheManager.getCache(appId.cacheKey())
        final String lowerCubeName = cubeName.toLowerCase()

        Cache.ValueWrapper item = cubeCache.get(lowerCubeName)
        if (item != null)
        {   // pull from cache
            Object value = item.get()
            return Boolean.FALSE == value ? null : value as NCube
        }

        // now even items with metaProperties(cache = 'false') can be retrieved
        // and normal app processing doesn't do two queries anymore.
        // used to do getCubeInfoRecords() -> dto
        // and then dto -> loadCube(id)
        NCube ncube = bean.call(beanName, 'getCube', [appId, cubeName]) as NCube
        if (ncube == null)
        {
            cubeCache.put(lowerCubeName, false)
            return null
        }
        return prepareCube(ncube)
    }

    private NCube prepareCube(NCube cube)
    {
        applyAdvices(cube)
        cacheCube(cube)
        return cube
    }

    //-- Advice --------------------------------------------------------------------------------------------------------

    /**
     * Associate Advice to all n-cubes that match the passed in regular expression.
     */
    void addAdvice(ApplicationID appId, String wildcard, Advice advice)
    {
        ApplicationID.validateAppId(appId)
        Cache current = adviceCacheManager.getCache(appId.cacheKey())
        current.put("${advice.name}/${wildcard}".toString(), advice)

        // Apply newly added advice to any fully loaded (hydrated) cubes.
        String regex = StringUtilities.wildcardToRegexString(wildcard)
        Pattern pattern = Pattern.compile(regex)

        if (ncubeCacheManager instanceof GCacheManager)
        {
            ((GCacheManager)ncubeCacheManager).applyToEntries(appId.cacheKey(), {String key, Object value ->
                if (value instanceof NCube)
                {   // apply advice to hydrated cubes
                    NCube ncube = value as NCube
                    Axis axis = ncube.getAxis('method')
                    addAdviceToMatchedCube(advice, pattern, ncube, axis)
                }
            })
        }
    }

    private void addAdviceToMatchedCube(Advice advice, Pattern pattern, NCube ncube, Axis axis)
    {
        if (axis != null)
        {   // Controller methods
            for (Column column : axis.columnsWithoutDefault)
            {
                String method = column.value.toString()
                String classMethod = "${ncube.name}.${method}()"
                if (pattern.matcher(classMethod).matches())
                {
                    ncube.addAdvice(advice, method)
                }
            }
        }

        // Add support for run() method (inline GroovyExpressions)
        String classMethod = ncube.name + '.run()'
        if (pattern.matcher(classMethod).matches())
        {
            ncube.addAdvice(advice, 'run')
        }
    }

    /**
     * Apply existing advices loaded into the NCubeRuntime, to the passed in
     * n-cube.  This allows advices to be added first, and then let them be
     * applied 'on demand' as an n-cube is loaded later.
     * @param appId ApplicationID
     * @param ncube NCube to which all matching advices will be applied.
     */
    private void applyAdvices(NCube ncube)
    {
        if (adviceCacheManager instanceof GCacheManager)
        {
            ((GCacheManager)adviceCacheManager).applyToEntries(ncube.applicationID.cacheKey(), {String key, Object value ->
                final Advice advice = value as Advice
                final String wildcard = key.replace(advice.name + '/', "")
                final String regex = StringUtilities.wildcardToRegexString(wildcard)
                final Axis axis = ncube.getAxis('method')
                addAdviceToMatchedCube(advice, Pattern.compile(regex), ncube, axis)
            })
        }
    }

    //-- Classloader / Classpath ---------------------------------------------------------------------------------------

    /**
     * Resolve the passed in String URL to a fully qualified URL object.  If the passed in String URL is relative
     * to a path in the sys.classpath, this method will perform (indirectly) the necessary HTTP HEAD requests to
     * determine which path it connects to.
     * @param url String url (relative or absolute)
     * @param input Map coordinate that the reuqested the URL (may include environment level settings that
     *              help sys.classpath select the correct ClassLoader.
     * @return URL fully qualified URL based on the passed in relative or absolute URL String.
     */
    URL getActualUrl(ApplicationID appId, String url, Map input)
    {
        ApplicationID.validateAppId(appId)
        if (StringUtilities.isEmpty(url))
        {
            throw new IllegalArgumentException("URL cannot be null or empty, attempting to resolve relative to absolute url for app: ${appId}")
        }
        String localUrl = url.toLowerCase()

        if (localUrl.startsWith('http:') || localUrl.startsWith('https:') || localUrl.startsWith('file:'))
        {   // Absolute URL
            try
            {
                return new URL(url)
            }
            catch (MalformedURLException e)
            {
                throw new IllegalArgumentException("URL is malformed: ${url}", e)
            }
        }
        else
        {
            URL actualUrl
            synchronized (url.intern())
            {
                URLClassLoader loader = getUrlClassLoader(appId, input)

                // Make URL absolute (uses URL roots added to NCubeRuntime)
                actualUrl = loader.getResource(url)
            }

            if (actualUrl == null)
            {
                String err = "Unable to resolve URL, make sure appropriate resource URLs are added to the sys.classpath cube, URL: ${url}, app: ${appId}"
                throw new IllegalArgumentException(err)
            }
            return actualUrl
        }
    }

    /**
     * Fetch the classloader for the given ApplicationID.
     */
    URLClassLoader getUrlClassLoader(ApplicationID appId, Map input)
    {
        NCube cpCube = getCube(appId, CLASSPATH_CUBE)

        if (cpCube == null)
        {   // No sys.classpath cube exists, just create regular GroovyClassLoader with no URLs set into it.
            // Scope the GroovyClassLoader per ApplicationID
            return getLocalClassloader(appId)
        }

        // duplicate input coordinate - no need to validate, that will be done inside cpCube.getCell() later.
        Map copy = new HashMap(input)

        final String envLevel = SystemUtilities.getExternalVariable('ENV_LEVEL')
        if (StringUtilities.hasContent(envLevel) && !doesMapContainKey(copy, 'env'))
        {   // Add in the 'ENV_LEVEL" environment variable when looking up sys.* cubes,
            // if there was not already an entry for it.
            copy.env = envLevel
        }
        if (!doesMapContainKey(copy, 'username'))
        {   // same as ENV_LEVEL, add if not already there.
            copy.username = System.getProperty('user.name')
        }
        Object urlCpLoader = cpCube.getCell(copy)

        if (urlCpLoader instanceof URLClassLoader)
        {
            return (URLClassLoader)urlCpLoader
        }

        throw new IllegalStateException('If the sys.classpath cube exists, it must return a URLClassLoader.')
    }

    URLClassLoader getLocalClassloader(ApplicationID appId)
    {
        GroovyClassLoader gcl = localClassLoaders[appId]
        if (gcl == null)
        {
            gcl = new CdnClassLoader(NCubeRuntime.class.classLoader)
            GroovyClassLoader classLoaderRef = localClassLoaders.putIfAbsent(appId, gcl)
            if (classLoaderRef != null)
            {
                gcl = classLoaderRef
            }
        }
        return gcl
    }

    private boolean doesMapContainKey(Map map, String key)
    {
        if (map instanceof TrackingMap)
        {
            Map wrappedMap = ((TrackingMap)map).getWrappedMap()
            return wrappedMap.containsKey(key)
        }
        return map.containsKey(key)
    }

    //-- Environment Variables -----------------------------------------------------------------------------------------

    Map<String, Object> getSystemParams()
    {
        final ConcurrentMap<String, Object> params = systemParams

        if (params != null)
        {
            return params
        }

        synchronized (NCubeRuntime.class)
        {
            if (systemParams == null)
            {
                String jsonParams = SystemUtilities.getExternalVariable(NCUBE_PARAMS)
                ConcurrentMap sysParamMap = new ConcurrentHashMap<>()

                if (StringUtilities.hasContent(jsonParams))
                {
                    try
                    {
                        sysParamMap = new ConcurrentHashMap<>((Map) JsonReader.jsonToJava(jsonParams, [(JsonReader.USE_MAPS): true] as Map))
                    }
                    catch (Exception ignored)
                    {
                        LOG.warn("Parsing of NCUBE_PARAMS failed: ${jsonParams}")
                    }
                }
                systemParams = sysParamMap
            }
        }
        return systemParams
    }

    void clearSysParams()
    {
        systemParams = null
    }

    //-- Resource APIs -------------------------------------------------------------------------------------------------

    static String getResourceAsString(String name) throws Exception
    {
        URL url = NCubeRuntime.class.getResource("/${name}")
        Path resPath = Paths.get(url.toURI())
        return new String(Files.readAllBytes(resPath), "UTF-8")
    }
    
    NCube getNCubeFromResource(ApplicationID appId, String name)
    {
        try
        {
            String json = getResourceAsString(name)
            NCube ncube = NCube.fromSimpleJson(json)
            ncube.applicationID = appId
            ncube.sha1()
            prepareCube(ncube)
            return ncube
        }
        catch (NullPointerException e)
        {
            throw new IllegalArgumentException("Could not find the file [n-cube]: ${name}, app: ${appId}", e)
        }
        catch (Exception e)
        {
            if (e instanceof RuntimeException)
            {
                throw (RuntimeException)e
            }
            throw new RuntimeException("Failed to load cube from resource: ${name}", e)
        }
    }

    /**
     * Still used in getNCubesFromResource
     */
    private Object[] getJsonObjectFromResource(String name) throws IOException
    {
        JsonReader reader = null
        try
        {
            URL url = NCubeRuntime.class.getResource('/' + name)
            File jsonFile = new File(url.file)
            InputStream input = new BufferedInputStream(new FileInputStream(jsonFile))
            reader = new JsonReader(input, true)
            return (Object[]) reader.readObject()
        }
        finally
        {
            IOUtilities.close(reader)
        }
    }

    List<NCube> getNCubesFromResource(ApplicationID appId, String name)
    {
        String lastSuccessful = ''
        try
        {
            Object[] cubes = getJsonObjectFromResource(name)
            List<NCube> cubeList = new ArrayList<>(cubes.length)

            for (Object cube : cubes)
            {
                JsonObject ncube = (JsonObject) cube
                String json = JsonWriter.objectToJson(ncube)
                NCube nCube = NCube.fromSimpleJson(json)
                nCube.applicationID = appId
                nCube.sha1()
                prepareCube(nCube)
                lastSuccessful = nCube.name
                cubeList.add(nCube)
            }

            return cubeList
        }
        catch (Exception e)
        {
            String s = "Failed to load cubes from resource: ${name}, last successful cube: ${lastSuccessful}"
            LOG.warn(s)
            throw new RuntimeException(s, e)
        }
    }

    //-- Validation ----------------------------------------------------------------------------------------------------

    protected void validateCube(NCube cube)
    {
        if (cube == null)
        {
            throw new IllegalArgumentException('NCube cannot be null')
        }
        NCube.validateCubeName(cube.name)
    }
}