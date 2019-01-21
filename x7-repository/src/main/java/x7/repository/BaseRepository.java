/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package x7.repository;

import org.apache.log4j.Logger;
import x7.core.async.CasualWorker;
import x7.core.async.IAsyncTask;
import x7.core.bean.*;
import x7.core.bean.condition.InCondition;
import x7.core.bean.condition.ReduceCondition;
import x7.core.bean.condition.RefreshCondition;
import x7.core.repository.X;
import x7.core.util.ExceptionUtil;
import x7.core.util.StringUtil;
import x7.core.web.Direction;
import x7.core.web.Page;
import x7.repository.api.X7Repository;
import x7.repository.exception.PersistenceException;
import x7.repository.mapper.Mapper;
import x7.repository.mapper.MapperFactory;
import x7.repository.redis.JedisConnector_Persistence;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Biz Repository extends BaseRepository
 *
 * @param <T>
 * @author Sim
 */
public abstract class BaseRepository<T> implements X7Repository<T> {

    private final static Logger logger = Logger.getLogger(BaseRepository.class);

    public final static String ID_MAP_KEY = "ID_MAP_KEY";

    private Class<T> clz;

    protected Class<T> getClz() {
        return clz;
    }

    public BaseRepository() {
        parse();
    }

    private void parse() {

        Type genType = getClass().getGenericSuperclass();

        Type[] params = ((ParameterizedType) genType).getActualTypeArguments();

        this.clz = (Class) params[0];

        EntityHolder.listAll().add(this.clz);
        logger.info("BaseRepository<T>, T: " + this.clz.getName());
        HealthChecker.repositoryList.add(this);
    }


    @Override
    public long createId() {

        final String name = clz.getName();
        final long id = JedisConnector_Persistence.getInstance().hincrBy(ID_MAP_KEY, name, 1);

        if (id == 0) {
            throw new PersistenceException("UNEXPECTED EXCEPTION WHILE CREATING ID");
        }

        CasualWorker.accept(new IAsyncTask() {

            @Override
            public void execute() throws Exception {
                IdGenerator generator = new IdGenerator();
                generator.setClzName(name);
                generator.setMaxId(id);
                StringBuilder sb = new StringBuilder();
                sb.append("update idGenerator set maxId = ").append(id).append(" where clzName = '").append(name)
                        .append("' and ").append(id).append(" > maxId ;");//sss

                try {
                    Parsed parsed = Parser.get(IdGenerator.class);
                    String sql = sb.toString();
                    ManuRepository.execute(generator, sql);
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }

        });

        return id;
    }

    @Override
    public boolean createBatch(List<T> objList) {
        return SqlRepository.getInstance().createBatch(objList);
    }

    @Override
    public long create(T obj) {
        /*
         * FIXME
         */
        System.out.println("BaesRepository.create: " + obj);

        long id = SqlRepository.getInstance().create(obj);

        return id;

    }

    @Override
    public boolean refresh(T obj) {

        Parsed parsed = Parser.get(this.clz);
        Field keyField = parsed.getKeyField(X.KEY_ONE);

        if (Objects.isNull(keyField))
            throw new RuntimeException("No PrimaryKey, UnSafe Refresh, try to invoke BaseRepository.refreshUnSafe(RefreshCondition<T> refreshCondition)");

        keyField.setAccessible(true);
        try {
            Object value = keyField.get(obj);
            if (Objects.isNull(value) || value.toString().equals("0"))
                throw new RuntimeException("UnSafe Refresh, try to invoke BaseRepository.refreshUnSafe(RefreshCondition<T> refreshCondition)");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("refresh safe, get keyOne exception");
        }


        return SqlRepository.getInstance().refresh(obj);
    }

    @Override
    public boolean refresh(RefreshCondition<T> refreshCondition) {

        refreshCondition.setClz(this.clz);
        Parsed parsed = Parser.get(this.clz);
        Field keyField = parsed.getKeyField(X.KEY_ONE);
        if (Objects.isNull(keyField))
            throw new PersistenceException("No PrimaryKey, UnSafe Refresh, try to invoke BaseRepository.refreshUnSafe(RefreshCondition<T> refreshCondition)");


        T obj = refreshCondition.getObj();
        CriteriaCondition criteriaCondition = refreshCondition.getCondition();

        boolean unSafe = false;//Safe

        if (Objects.nonNull(obj)) {
            keyField.setAccessible(true);
            try {
                Object value = keyField.get(obj);
                if (Objects.isNull(value)) {
                    unSafe = true;//UnSafe
                } else if (value.toString().equals("0")) {
                    unSafe = true;//UnSafe
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("refresh safe, get keyOne exception");
            }

        } else {
            unSafe = true;//UnSafe
        }

        if (unSafe) {
            String key = parsed.getKey(X.KEY_ONE);
            for (Criteria.X x : criteriaCondition.getListX()) {
                if (key.equals(x.getKey())) {
                    Object value = x.getValue();
                    if (Objects.nonNull(value) && !value.toString().equals("0")) {
                        unSafe = false;//Safe
                    }
                }
            }
        }

        if (unSafe)
            throw new PersistenceException("UnSafe Refresh, try to invoke BaseRepository.refreshUnSafe(RefreshCondition<T> refreshCondition)");

        return SqlRepository.getInstance().refresh(refreshCondition);
    }

    @Override
    public boolean refreshUnSafe(RefreshCondition<T> refreshCondition) {
        refreshCondition.setClz(this.clz);
        return SqlRepository.getInstance().refresh(refreshCondition);
    }


    @Override
    public void remove(T obj) {
        SqlRepository.getInstance().remove(obj);
    }

    @Override
    public T get(long idOne) {

        return SqlRepository.getInstance().get(clz, idOne);
    }

    @Override
    public List<T> list() {

        return SqlRepository.getInstance().list(clz);
    }

    @Override
    public List<T> list(T conditionObj) {

        if (conditionObj instanceof Criteria.ResultMapped) {
            throw new RuntimeException(
                    "Exception supported, no pagination not to invoke SqlRepository.getInstance().list(criteriaJoinalbe);");
        }

        return SqlRepository.getInstance().list(conditionObj);
    }

    @Override
    public T getOne(T conditionObj, String orderBy, Direction sc) {

        return SqlRepository.getInstance().getOne(conditionObj, orderBy, sc);
    }

    @Override
    public T getOne(T conditionObj) {

        T t = SqlRepository.getInstance().getOne(conditionObj);
        return t;
    }

    @Override
    public void refreshCache() {
        SqlRepository.getInstance().refreshCache(clz);
    }

    @Override
    public Object reduce(ReduceCondition reduceCondition) {
        reduceCondition.setClz(this.clz);
        return SqlRepository.getInstance().reduce(reduceCondition);
    }


    @Override
    public List<T> in(InCondition inCondition) {
        if (inCondition.getInList().isEmpty())
            return new ArrayList<T>();

        inCondition.setClz(this.clz);

        return SqlRepository.getInstance().in(inCondition);
    }

    private static <T> List<T> in0(InCondition inCondition) {
        if (inCondition.getInList().isEmpty())
            return new ArrayList<T>();

        return SqlRepository.getInstance().in(inCondition);
    }

    @Override
    public Page<T> find(Criteria criteria) {

        if (criteria instanceof Criteria.ResultMapped)
            throw new RuntimeException("Codeing Exception: maybe {Criteria.ResultMapped criteria = builder.get();} instead of {Criteria criteria = builder.get();}");
        return SqlRepository.getInstance().find(criteria);
    }


    @Override
    public Page<Map<String, Object>> find(Criteria.ResultMapped criteria) {

        return SqlRepository.getInstance().find(criteria);
    }


    @Override
    public List<Map<String, Object>> list(Criteria.ResultMapped resultMapped) {
        return SqlRepository.getInstance().list(resultMapped);
    }

    @Override
    public List<T> list(Criteria criteria) {

        if (criteria instanceof Criteria.ResultMapped)
            throw new RuntimeException("Codeing Exception: maybe {Criteria.ResultMapped criteria = builder.get();} instead of {Criteria criteria = builder.get();}");

        return SqlRepository.getInstance().list(criteria);
    }

    private static <T> List<T> list0(Criteria criteria) {

        if (criteria instanceof Criteria.ResultMapped)
            throw new RuntimeException("Codeing Exception: maybe {Criteria.ResultMapped criteria = builder.get();} instead of {Criteria criteria = builder.get();}");

        return SqlRepository.getInstance().list(criteria);
    }


    public static class EntityHolder {
        private final static List<Class> list = new ArrayList<>();

        public static List<Class> listAll() {
            return list;
        }
    }

    public static class HealthChecker {

        private static List<BaseRepository> repositoryList = new ArrayList<BaseRepository>();

        protected static void onStarted() {

            for (BaseRepository repository : repositoryList) {
                Parser.get(repository.getClz());
            }

            Parsed parsed = Parser.get(IdGenerator.class);


            String sql = "CREATE TABLE IF NOT EXISTS `idGenerator` ( "
                    + "`clzName` varchar(120) NOT NULL, "
                    + "`maxId` bigint(13) DEFAULT NULL, "
                    + "PRIMARY KEY (`clzName`) "
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8 ";

            try {
                ManuRepository.execute(new IdGenerator(), sql);
            } catch (Exception e) {

            }

            System.out.println("-------------------------------------------------");

            boolean flag = false;

            for (BaseRepository repository : repositoryList) {

                try {
                    Class clz = repository.getClz();
                    String createSql = MapperFactory.tryToCreate(clz);
                    String test = MapperFactory.getSql(clz, Mapper.CREATE);
                    if (StringUtil.isNullOrEmpty(test)) {
                        System.out.println("FAILED TO START X7-REPOSITORY, check Bean: " + clz);
                        System.exit(1);
                    }

                    if (DbType.value.equals(DbType.MYSQL)) {
                        System.out.println("________ table check: " + clz.getName());
                        System.out.println("________ SQL   check: " + createSql);
                        SqlRepository.getInstance().execute(clz.newInstance(), createSql);
                    }

                    Parsed clzParsed = Parser.get(clz);
                    Field f = clzParsed.getKeyField(X.KEY_ONE);
                    if (f.getType() == String.class)
                        continue;
                    final String name = clz.getName();
                    IdGenerator generator = new IdGenerator();
                    generator.setClzName(name);
                    List<IdGenerator> list = SqlRepository.getInstance().list(generator);
                    if (list.isEmpty()) {
                        System.out.println("________ id init: " + generator.getClzName());
                        generator.setMaxId(0);
                        SqlRepository.getInstance().create(generator);
                    }

                } catch (Exception e) {
                    flag |= true;
//					e.printStackTrace();
                }
            }

            logger.info("X7 Repository " + (flag ? "still " : "") + "started" + (flag ? " OK, wtih some problem" : ""));

        }
    }

    @Override
    public <WITH> List<DomainObject<T, WITH>> listDomainObject(Criteria.DomainObjectCriteria domainObjectCriteria) {


        if (domainObjectCriteria.getRelativeClz() == null){

            if (domainObjectCriteria.getKnownMainIdList() == null || domainObjectCriteria.getKnownMainIdList().isEmpty()){
                return DomainObjectRepositoy.listDomainObject_NonRelative(domainObjectCriteria);
            }else{
                return DomainObjectRepositoy.listDomainObject_Known_NonRelative(domainObjectCriteria);
            }

        }else{
            if (domainObjectCriteria.getKnownMainIdList() == null || domainObjectCriteria.getKnownMainIdList().isEmpty()){
                return DomainObjectRepositoy.listDomainObject_HasRelative(domainObjectCriteria);
            }else{
                return DomainObjectRepositoy.listDomainObject_Known_HasRelative(domainObjectCriteria);
            }
        }

    }



    public static class DomainObjectRepositoy {

        protected static <T,WITH> List<DomainObject<T, WITH>> listDomainObject_Known_HasRelative(Criteria.DomainObjectCriteria domainObjectCriteria) {

            try {

                /*
                 * knownMainIdList step 1
                 */
                List<Object> mainInList = domainObjectCriteria.getKnownMainIdList();
                List<T> mainList = null;


                /*
                 * step 2  if relativeClass
                 */
                Parsed withParsed = Parser.get(domainObjectCriteria.getWithClz());
                Parsed relativeParsed = Parser.get(domainObjectCriteria.getRelativeClz());

                List relativeList = null;
                List withList = null;

                InCondition relativeInCondition = new InCondition(domainObjectCriteria.getMainPropperty(), mainInList);
                relativeInCondition.setClz(domainObjectCriteria.getRelativeClz());
                relativeList = in0(relativeInCondition);

                BeanElement relativeWithBe = relativeParsed.getElement(domainObjectCriteria.getWithProperty());

                List<Object> withInList = new ArrayList<>();
                for (Object r : relativeList) {
                    Object in = relativeWithBe.getMethod.invoke(r);
                    withInList.add(in);
                }

                String key = withParsed.getKey(X.KEY_ONE);

                InCondition withInCondition = new InCondition(key, withInList);
                withInCondition.setClz(domainObjectCriteria.getWithClz());
                withList = in0(withInCondition);


                List<DomainObject<T, WITH>> list = new ArrayList<>();



                /*
                 * result assemble step3
                 */
                BeanElement relatievMainBe = domainObjectCriteria.getRelativeClz() == null ? null :
                        relativeParsed.getElement(domainObjectCriteria.getMainPropperty());

                Field withKeyF = withParsed.getKeyField(X.KEY_ONE);
                withKeyF.setAccessible(true);

                BeanElement wBe = withParsed.getElement(domainObjectCriteria.getMainPropperty());// maybe null

                for (Object mainKeyOne : domainObjectCriteria.getKnownMainIdList()) {

                    List withListOne = new ArrayList();


                    for (Object r : relativeList) {
                        Object oRelative = relatievMainBe.getMethod.invoke(r);
                        if (mainKeyOne.toString().equals(oRelative.toString())) {
                            Object relativeWithKey = relativeWithBe.getMethod.invoke(r);

                            for (Object w : withList) {
                                Object withId = withKeyF.get(w);
                                if (relativeWithKey.toString().equals(withId.toString())) {
                                    withListOne.add(w);
                                }
                            }

                        }
                    }


                    DomainObject domainObject = new DomainObject();
                    domainObject.setMainId(mainKeyOne);

                    domainObject.setWithList(withListOne);

                    list.add(domainObject);
                }


                return list;
            } catch (Exception e) {
                throw new RuntimeException(ExceptionUtil.getMessage(e));
            }

        }

        protected static <T,WITH> List<DomainObject<T, WITH>> listDomainObject_Known_NonRelative(Criteria.DomainObjectCriteria domainObjectCriteria) {

            try {
                /*
                 * knownMainIdList step 1
                 */
                List<Object> mainInList = domainObjectCriteria.getKnownMainIdList();
                List<T> mainList = null;


                /*
                 * step 2  if relativeClass
                 */
                Parsed withParsed = Parser.get(domainObjectCriteria.getWithClz());
                Parsed relativeParsed = Parser.get(domainObjectCriteria.getRelativeClz());


                InCondition withInCondition = new InCondition(domainObjectCriteria.getMainPropperty(), mainInList);
                withInCondition.setClz(domainObjectCriteria.getWithClz());
                List withList = in0(withInCondition);


                List<DomainObject<T, WITH>> list = new ArrayList<>();



                /*
                 * result assemble step3
                 */

                Field withKeyF = withParsed.getKeyField(X.KEY_ONE);
                withKeyF.setAccessible(true);

                BeanElement wBe = withParsed.getElement(domainObjectCriteria.getMainPropperty());// maybe null

                for (Object mainKeyOne : domainObjectCriteria.getKnownMainIdList()) {

                    List withListOne = new ArrayList();


                    for (Object w : withList) {
                        Object withR = wBe.getMethod.invoke(w);
                        if (mainKeyOne.toString().equals(withR.toString())) {
                            withListOne.add(w);
                        }
                    }


                    DomainObject domainObject = new DomainObject();
                    domainObject.setMainId(mainKeyOne);

                    domainObject.setWithList(withListOne);

                    list.add(domainObject);
                }


                return list;
            } catch (Exception e) {
                throw new RuntimeException(ExceptionUtil.getMessage(e));
            }

        }


        protected static <T,WITH> List<DomainObject<T, WITH>> listDomainObject_HasRelative(Criteria.DomainObjectCriteria domainObjectCriteria) {

            try {

                /*
                 * knownMainIdList step 1
                 */
                List<Object> mainInList = new ArrayList<>();
                List<T> mainList = null;
                if (mainInList == null || mainInList.isEmpty()) {

                    mainList = list0((Criteria) domainObjectCriteria);

                    Parsed mainParsed = Parser.get(domainObjectCriteria.getClz());
                    Field mainField = mainParsed.getKeyField(X.KEY_ONE);
                    mainField.setAccessible(true);

                    for (Object t : mainList) {
                        Object in = mainField.get(t);
                        mainInList.add(in);
                    }
                }


                /*
                 * step 2  if relativeClass
                 */
                Parsed withParsed = Parser.get(domainObjectCriteria.getWithClz());
                Parsed relativeParsed = Parser.get(domainObjectCriteria.getRelativeClz());

                List relativeList = null;
                List withList = null;


                InCondition relativeInCondition = new InCondition(domainObjectCriteria.getMainPropperty(), mainInList);
                relativeInCondition.setClz(domainObjectCriteria.getRelativeClz());
                relativeList = in0(relativeInCondition);

                BeanElement relativeWithBe = relativeParsed.getElement(domainObjectCriteria.getWithProperty());

                List<Object> withInList = new ArrayList<>();
                for (Object r : relativeList) {
                    Object in = relativeWithBe.getMethod.invoke(r);
                    withInList.add(in);
                }

                String key = withParsed.getKey(X.KEY_ONE);

                InCondition withInCondition = new InCondition(key, withInList);
                withInCondition.setClz(domainObjectCriteria.getWithClz());
                withList = in0(withInCondition);

                List<DomainObject<T, WITH>> list = new ArrayList<>();


                /*
                 * result assemble step3
                 */
                BeanElement relatievMainBe = domainObjectCriteria.getRelativeClz() == null ? null :
                        relativeParsed.getElement(domainObjectCriteria.getMainPropperty());

                Field withKeyF = withParsed.getKeyField(X.KEY_ONE);
                withKeyF.setAccessible(true);


                Parsed mainParsed = Parser.get(domainObjectCriteria.getClz());
                Field mainField = mainParsed.getKeyField(X.KEY_ONE);
                mainField.setAccessible(true);

                BeanElement wBe = withParsed.getElement(domainObjectCriteria.getMainPropperty());

                for (Object main : mainList) {

                    Object mainKeyOne = mainField.get(main);

                    List withListOne = new ArrayList();


                    for (Object r : relativeList) {
                        Object oRelative = relatievMainBe.getMethod.invoke(r);
                        if (mainKeyOne.toString().equals(oRelative.toString())) {
                            Object relativeWithKey = relativeWithBe.getMethod.invoke(r);

                            for (Object w : withList) {
                                Object withId = withKeyF.get(w);
                                if (relativeWithKey.toString().equals(withId.toString())) {
                                    withListOne.add(w);
                                }
                            }

                        }
                    }


                    DomainObject domainObject = new DomainObject();
                    domainObject.setMain(main);
                    domainObject.setWithList(withListOne);

                    list.add(domainObject);
                }


                return list;
            } catch (Exception e) {
                throw new RuntimeException(ExceptionUtil.getMessage(e));
            }

        }


        protected static <T,WITH> List<DomainObject<T, WITH>> listDomainObject_NonRelative(Criteria.DomainObjectCriteria domainObjectCriteria) {

            try {

                /*
                 * knownMainIdList step 1
                 */
                List<Object> mainInList = new ArrayList<>();
                List<T> mainList = null;
                if (mainInList == null || mainInList.isEmpty()) {

                    mainList = list0((Criteria) domainObjectCriteria);

                    Parsed mainParsed = Parser.get(domainObjectCriteria.getClz());
                    Field mainField = mainParsed.getKeyField(X.KEY_ONE);
                    mainField.setAccessible(true);

                    for (Object t : mainList) {
                        Object in = mainField.get(t);
                        mainInList.add(in);
                    }
                }


                /*
                 * step 2  if relativeClass
                 */
                Parsed withParsed = Parser.get(domainObjectCriteria.getWithClz());
                Parsed relativeParsed = Parser.get(domainObjectCriteria.getRelativeClz());

                List withList = null;


                InCondition withInCondition = new InCondition(domainObjectCriteria.getMainPropperty(), mainInList);
                withInCondition.setClz(domainObjectCriteria.getWithClz());
                withList = in0(withInCondition);


                List<DomainObject<T, WITH>> list = new ArrayList<>();


                /*
                 * result assemble step3
                 */
                BeanElement relatievMainBe = domainObjectCriteria.getRelativeClz() == null ? null :
                        relativeParsed.getElement(domainObjectCriteria.getMainPropperty());

                Field withKeyF = withParsed.getKeyField(X.KEY_ONE);
                withKeyF.setAccessible(true);


                Parsed mainParsed = Parser.get(domainObjectCriteria.getClz());
                Field mainField = mainParsed.getKeyField(X.KEY_ONE);
                mainField.setAccessible(true);

                BeanElement wBe = withParsed.getElement(domainObjectCriteria.getMainPropperty());

                for (Object main : mainList) {

                    Object mainKeyOne = mainField.get(main);

                    List withListOne = new ArrayList();


                    for (Object w : withList) {
                        Object withR = wBe.getMethod.invoke(w);
                        if (mainKeyOne.toString().equals(withR.toString())) {
                            withListOne.add(w);
                        }
                    }


                    DomainObject domainObject = new DomainObject();
                    domainObject.setMain(main);
                    domainObject.setWithList(withListOne);

                    list.add(domainObject);
                }


                return list;
            } catch (Exception e) {
                throw new RuntimeException(ExceptionUtil.getMessage(e));
            }

        }


    }

}
